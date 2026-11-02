/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.examples.deepresearch;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.FilesystemInterceptor;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.LargeResultEvictionInterceptor;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.PatchToolCallsInterceptor;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.SubAgentInterceptor;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.SubAgentSpec;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.contextediting.ContextEditingInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.todolist.TodoListInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static com.alibaba.cloud.ai.examples.deepresearch.DeepResearchAgent.Prompts.researchInstructions;
import static com.alibaba.cloud.ai.examples.deepresearch.DeepResearchAgent.Prompts.subCritiquePrompt;
import static com.alibaba.cloud.ai.examples.deepresearch.DeepResearchAgent.Prompts.subResearchPrompt;
import static com.alibaba.cloud.ai.examples.deepresearch.DeepResearchAgent.Prompts.subWriterPrompt;
import static com.alibaba.cloud.ai.examples.deepresearch.DeepResearchAgent.Prompts.subReviserPrompt;

public class DeepResearchAgent {
	private static final String BASE_AGENT_PROMPT =
			"In order to complete the objective that the user asks of you, " +
					"you have access to a number of standard tools.\n\n" +
					"**Tool argument format (CRITICAL):** tools with object parameters (`read_file`, " +
					"`write_file`, `edit_file`, `grep`) must receive a JSON object, never a bare string. " +
					"To inspect files, prefer `read_file` on a known path.";

	private String systemPrompt;
	// Model-tier routing: plus for the main ReAct loop and research sub-agents,
	// flash for history summarization, max for report critique.
	private ChatModel chatModel;
	private ChatModel flashModel;
	private ChatModel maxModel;

	// Redis-backed checkpoint store: session state survives service restarts.
	private RedissonClient redisson;

	// Interceptors
	private LargeResultEvictionInterceptor largeResultEvictionInterceptor;
	private FilesystemInterceptor filesystemInterceptor;
	private TodoListInterceptor todoListInterceptor;
	private PatchToolCallsInterceptor patchToolCallsInterceptor;
	private ContextEditingInterceptor contextEditingInterceptor;
	private ToolRetryInterceptor toolRetryInterceptor;

	// Hooks
	private SummarizationHook summarizationHook;
	private HumanInTheLoopHook humanInTheLoopHook;
	private ToolCallLimitHook toolCallLimitHook;
	private ShellToolAgentHook shellToolAgent;

//	private ShellTool shellTool = ShellTool.builder().build();

	public DeepResearchAgent() {
		// OpenAI-compatible DashScope endpoint. The native DashScope API rejects tool
		// arguments that are not JSON objects (ls/glob take a bare String), returning
		// HTTP 400 and permanently poisoning the session; compatible-mode accepts them.
		OpenAiApi openAiApi = OpenAiApi.builder()
				.baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
				.apiKey(System.getenv("AI_DASHSCOPE_API_KEY"))
				.build();
		// Create chat model instances for each tier
		this.chatModel = buildModel(openAiApi, "qwen-plus");
		this.flashModel = buildModel(openAiApi, "qwen-flash");
		this.maxModel = buildModel(openAiApi, "qwen-max");

		// Connect to local Redis for checkpoint persistence.
		Config redissonConfig = new Config();
		redissonConfig.useSingleServer().setAddress("redis://127.0.0.1:6379");
		this.redisson = Redisson.create(redissonConfig);

		this.systemPrompt = researchInstructions + "\n\n" + BASE_AGENT_PROMPT;

		// Initialize interceptors
		this.largeResultEvictionInterceptor = LargeResultEvictionInterceptor
				.builder()
				.excludeFilesystemTools()
				.toolTokenLimitBeforeEvict(5000)
				.build();

		this.filesystemInterceptor = FilesystemInterceptor.builder()
				.readOnly(false)
				.build();

		this.todoListInterceptor = TodoListInterceptor.builder().build();

		this.patchToolCallsInterceptor = PatchToolCallsInterceptor.builder().build();

		this.toolRetryInterceptor = ToolRetryInterceptor.builder()
				.maxRetries(1).onFailure(ToolRetryInterceptor.OnFailureBehavior.RETURN_MESSAGE)
				.build();

		// Initialize hooks
		this.summarizationHook = SummarizationHook.builder()
				.model(flashModel) // cheap tier for history summarization
				.maxTokensBeforeSummary(120000)
				.messagesToKeep(6)
				.build();

		// HITL disabled for filesystem tools: every write_file interrupt closed the
		// SSE stream, and the client cannot reliably re-attach between parallel
		// sub-agents, which left runs without findings/final_report on disk.
		// Writes are confined to the local /workspace directory, so auto-approving
		// them keeps a single /run_sse connection alive until the report is done.
		this.humanInTheLoopHook = HumanInTheLoopHook.builder()
				.build();

		// Raised from 25 to 60: a full pass (question write + 5 parallel task
		// dispatches + writer + critique + reviser, plus retries) exhausted the
		// old limit before the writer stage could run.
		this.toolCallLimitHook = ToolCallLimitHook.builder()
				.runLimit(60)
				.build();

		this.shellToolAgent = ShellToolAgentHook.builder().build();

		this.contextEditingInterceptor = ContextEditingInterceptor.builder()
				.trigger(10000)
				.clearAtLeast(6000)
				.keep(4)
				.excludeTools("write_todos")
				.build();

	}

	private static SubAgentSpec createCritiqueAgent(ChatModel model, String subCritiquePrompt) {
		return SubAgentSpec.builder()
				.name("critique-agent")
				.description("Used to critique the final report. Provide information about " +
						"how you want the report to be critiqued.")
				.systemPrompt(subCritiquePrompt)
				.model(model)
				// Sub-agents build their own ReactAgent, so interceptors must be
				// attached per spec or the ls/glob argument fix never runs here.
				.interceptors(List.of(new StringArgToolCallInterceptor()))
				.enableLoopingLog(true)
				.build();
	}

	private static SubAgentSpec createWriterAgent(String subWriterPrompt) {
		return SubAgentSpec.builder()
				.name("writer-agent")
				.description("Dedicated report writer. Call ONCE after all research-agent tasks are " +
						"complete. It reads /workspace/question.txt and all /workspace/findings-*.md files and writes " +
						"/workspace/final_report.md. Do not give it web-research instructions.")
				.systemPrompt(subWriterPrompt)
				.interceptors(List.of(new StringArgToolCallInterceptor()))
				.enableLoopingLog(true)
				.build();
	}

	private static SubAgentSpec createReviserAgent(String subReviserPrompt) {
		return SubAgentSpec.builder()
				.name("reviser-agent")
				.description("Dedicated report reviser. Call after critique-agent returns feedback. " +
						"It reads /workspace/critique.md and edits /workspace/final_report.md in place." +
						"")
				.systemPrompt(subReviserPrompt)
				.interceptors(List.of(new StringArgToolCallInterceptor()))
				.enableLoopingLog(true)
				.build();
	}

	private static ChatModel buildModel(OpenAiApi openAiApi, String modelName) {
		return OpenAiChatModel.builder()
				.openAiApi(openAiApi)
				.defaultOptions(OpenAiChatOptions.builder().model(modelName).build())
				.build();
	}

	private static SubAgentSpec createResearchAgent(List<ToolCallback> toolsFromMcp, String subResearchPrompt) {
		return SubAgentSpec.builder()
				.name("research-agent")
				.description("Used to research in-depth questions. Only give one topic at a time. " +
						"Break down large topics into components and call multiple research agents " +
						"in parallel for each sub-question.")
				.systemPrompt(subResearchPrompt)
				.tools(toolsFromMcp)
				.interceptors(List.of(new StringArgToolCallInterceptor()))
				.enableLoopingLog(true)
				.build();
	}

	public ReactAgent getResearchAgent(List<ToolCallback> toolsFromMcp) {
		// Build the ReactAgent with all interceptors
		return ReactAgent.builder()
				.name("DeepResearchAgent")
				.model(chatModel)
				.tools(toolsFromMcp)
				.systemPrompt(systemPrompt)
				.enableLogging(true)
				// Force-parallel dispatch: run all tool calls (task subagents,
				// searches) in one assistant message concurrently.
				.parallelToolExecution(true)
				.interceptors(todoListInterceptor,
						filesystemInterceptor,
						// Must run before tool execution: rewrites ls/glob arguments
						// into JSON string literals, otherwise DashScope rejects the
						// request with HTTP 400.
						new StringArgToolCallInterceptor(),
						largeResultEvictionInterceptor,
						patchToolCallsInterceptor,
//						contextEditingInterceptor,
						toolRetryInterceptor,
						subAgentAsInterceptors(toolsFromMcp))
				// HITL re-enabled with the risk-tiered policy above: low-risk
					// tool calls pass through without interrupt, so the tool-call
					// limit counter is only reset on rare human approvals.
					.hooks(summarizationHook, toolCallLimitHook, humanInTheLoopHook)
				.saver(RedisSaver.builder().redisson(redisson).build())
				.build();

	}

	private Interceptor subAgentAsInterceptors(List<ToolCallback> toolsFromMcp) {
		SubAgentSpec researchAgent = createResearchAgent(toolsFromMcp, subResearchPrompt);
		SubAgentSpec critiqueAgent = createCritiqueAgent(maxModel, subCritiquePrompt);
		SubAgentSpec writerAgent = createWriterAgent(subWriterPrompt);
		SubAgentSpec reviserAgent = createReviserAgent(subReviserPrompt);

		SubAgentInterceptor.Builder subAgentBuilder = SubAgentInterceptor.builder()
				.defaultModel(chatModel)
				.defaultInterceptors(
						todoListInterceptor,
						filesystemInterceptor,
//						contextEditingInterceptor,
						patchToolCallsInterceptor,
						largeResultEvictionInterceptor
				)
				.defaultHooks(summarizationHook, toolCallLimitHook)
				.addSubAgent(researchAgent)
				.includeGeneralPurpose(true)
				.addSubAgent(critiqueAgent)
				.addSubAgent(writerAgent)
				.addSubAgent(reviserAgent);
		return subAgentBuilder.build();
	}

	public static class Prompts {
		// Main research instructions
		public static String researchInstructions = """
				You are an expert research orchestrator. You plan the research and dispatch specialized
				sub-agents stage by stage. You NEVER write or edit the report yourself.

				**Pipeline (strict order):**
				1. Write the original user question to `/workspace/question.txt` for reference.
				2. Plan: break the topic into 3-5 specific, independent sub-questions that all stay
				   strictly inside the user's topic. Each sub-question must name the exact subjects being
				   compared (e.g. `Spring AI Alibaba` and `LangChain4j`); never drift to unrelated
				   frameworks or technologies.
				3. Research: MANDATORY dispatch ALL sub-questions as separate `task` tool calls with
				   subagent_type `research-agent` in ONE single response so they run in parallel.
				   Never send just one task call per response. Each `description` MUST be a short
				   plain-ASCII phrase of at most 12 words naming the sub-question and its findings file,
				   e.g. `LangChain4j orchestration design. Write /workspace/findings-2.md.`
				   No long sentences, no extra clauses, no non-ASCII characters, no quotes.
				4. Write: after ALL research tasks return, dispatch ONE `task` call with subagent_type
				   `writer-agent`, telling it to read `/workspace/question.txt` and all
				   `/workspace/findings-*.md` files and write the complete report to
				   `/workspace/final_report.md`.
				5. Review: dispatch ONE `task` call with subagent_type `critique-agent` to critique
				   `/workspace/final_report.md`.
				6. Revise: dispatch ONE `task` call with subagent_type `reviser-agent`, telling it to read
				   `/workspace/critique.md` and edit `/workspace/final_report.md` in place. NEVER paste the
				   critique text into the task description.
				7. Repeat steps 5-6 at most ONE more time, then finish with a short conclusion.

				**Task-call hygiene (CRITICAL):** the `task` tool arguments must be valid JSON. Keep every
				`description` to ONE short line of plain text — no newlines, no double quotes, no backticks,
				no code blocks, no pasted report or critique content. Put any long content in a file and tell
				the sub-agent to read that file by name.

				Keep your own messages short: plan, dispatch tasks, collect results, finish.
				All report formatting rules live in the writer-agent prompt; do not duplicate them.

				If the `writer-agent` task returns an exception or otherwise fails, do NOT write or edit
				`/workspace/final_report.md` yourself. Retry the writer task once; if it fails again, stop and report
				the failure to the user instead of producing a fallback report.
				""";

		// Sub-agent prompt for research
		public static String subResearchPrompt = """
				You are a dedicated researcher. Your job is to conduct research based on the user's questions.

				Conduct thorough research. The task description names a findings file (e.g. `/workspace/findings-1.md`) —
				write your complete, self-contained findings in Markdown to that file with `write_file`.

				**Search (CRITICAL):** this sub-question is NOT answerable from memory. Call the web search tool
				(`bailian_web_search`) at least TWICE with different, specific queries before writing anything.
				Base every statement on the pages the search returns.

				**Citations (CRITICAL):** after every factual claim put the URL you actually got back from the
				search tool, in `[Title](URL)` form. End the findings file with a `## Sources` section listing
				those URLs. If no search result supports a claim, write "not verified" next to it — never
				state it as fact, and never invent a URL.

				**Source quality (CRITICAL):** cite only official documentation, the project's GitHub
				repository, or official examples. Do NOT cite CSDN, Zhihu, Medium, or personal blogs —
				they routinely contain invented APIs and configuration keys. If a claim cannot be verified
				from an official source, write "not verified" instead of guessing. Never invent class names,
				methods, or configuration properties. Keep your research strictly inside the sub-question
				you were given; do not drift to unrelated frameworks or technologies.

				**Tool arguments (CRITICAL):** every tool call must pass a JSON object, never a bare string.
				`write_file` takes `file_path` and `content` parameters.

				Then reply to the orchestrator with a SHORT summary: the findings file path plus the key
				points in a few lines. The full content lives in the findings file.
				""";

		// Sub-agent prompt for critique
		public static String subCritiquePrompt = """
				You are a dedicated editor. You are being tasked to critique a report.
				
				You can find the report at `/workspace/final_report.md`.
				You can find the question/topic for this report at `/workspace/question.txt`.
				
				The user may ask for specific areas to critique the report in.
				Write your detailed critique to `/workspace/critique.md` with `write_file`, then reply to the
				orchestrator with a one-line confirmation. Long critique text must go in the file,
				never in the task description.
				
				You can use the search tool to search for information, if that will help you critique the report.
				
				Do not write to the `/workspace/final_report.md` yourself.
				
				Check the report for: correct section structure; every fact attributed to the RIGHT
				framework (never place framework A's mechanisms under framework B); essay/textbook style
				(text-heavy, not bullets); comprehensiveness; analytical depth; and direct relevance to
				the question in `/workspace/question.txt`.

				**Required output format (CRITICAL):** write a concrete critique, not an empty checklist.
				For every problem, give three things together:
				1. the section heading or a short quoted sentence where it occurs
				2. why it is wrong (state the correct fact when you know it)
				3. the concrete fix to apply
				List at least 3 problems if any exist. Never output `- [ ]` items or bare headings only.
				If the report is genuinely good, say so in one line and still list its 2-3 weakest points.
				""";

		// Sub-agent prompt for the dedicated report writer (pipeline stage 4)
		public static String subWriterPrompt = """
				You are a dedicated report writer. Do NOT search the web; your only inputs are files.

				**Tool-calling rule (CRITICAL):** Issue only ONE tool call per message. Never put two
				tool calls in the same response — do them one by one.
					Every tool argument must be a JSON object, never a bare string:
					`read_file` takes a `file_path` parameter set to `/workspace/findings-1.md`.

				1. Read `/workspace/question.txt` to understand the research topic.
				2. Read the findings files with `read_file`: `/workspace/findings-1.md`, `/workspace/findings-2.md`,
				   `/workspace/findings-3.md`, `/workspace/findings-4.md`, `/workspace/findings-5.md`.
				   Do NOT call `ls`. Skip any findings file that does not exist and keep going.
				3. Synthesize ALL findings into one polished Markdown report and write it to
				   `/workspace/final_report.md` with `write_file`.

				**Report Format Requirements:**
				- CRITICAL: Write in the SAME language as the user's question!
				- Use clear Markdown with proper structure (# for title, ## for sections, ### for subsections)
				- Include specific facts and insights from the findings
				- Reference sources using [Title](URL) format
				- Provide balanced, thorough analysis
				- Be comprehensive - users expect detailed, in-depth answers
				- End with a "### Sources" section listing all references

				**Citation Rules:**
				- Never invent a URL. Copy only URLs that literally appear in the findings files. If a claim
				  has no URL behind it, mark it "not verified" and leave it out of the Sources list. If no
				  findings file contains any URL, write the Sources section as one line saying that no
				  verifiable sources were available.
				- Assign each unique URL a single citation number [1], [2], etc.
				- Number sources sequentially without gaps in the final list
				- Each source should be a separate list item
				- Format: [1] Source Title: URL

				Structure the report appropriately for the question type:
				- Comparison: intro → overview A → overview B → comparison → conclusion
				- List: Simple numbered/bulleted list or separate sections per item
				- Overview/Summary: intro → concept 1 → concept 2 → ... → conclusion
				- Analysis: thesis → evidence → analysis → conclusion

				When done, reply with a one-line confirmation that /workspace/final_report.md was written.
				""";

		// Sub-agent prompt for the dedicated reviser (pipeline stage 6)
		public static String subReviserPrompt = """
				You are a dedicated editor-reviser. The critique feedback is in `/workspace/critique.md`.

				1. Read `/workspace/critique.md`, `/workspace/final_report.md` and `/workspace/question.txt`.
				2. Apply the critique: fix structure, completeness, analysis depth and citations.
				   Use `edit_file` to revise `/workspace/final_report.md` in place.
				3. Do NOT run new web research unless the critique points out a factual error;
				   your job is revising the existing text.

				Keep the report in the SAME language as the question.
				When done, reply with a short list of the revisions you applied.
				""";
	}
}
