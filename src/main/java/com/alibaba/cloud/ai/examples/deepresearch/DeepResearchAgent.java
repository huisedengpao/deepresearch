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

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
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
					"you have access to a number of standard tools.";

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
		// Create DashScopeApi instance using the API key from environment variable
		DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(System.getenv("AI_DASHSCOPE_API_KEY")).build();
		// Create DashScope ChatModel instances for each tier
		this.chatModel = buildModel(dashScopeApi, "qwen-plus");
		this.flashModel = buildModel(dashScopeApi, "qwen-flash");
		this.maxModel = buildModel(dashScopeApi, "qwen-max");

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

		// Risk-tiered HITL policy: only destructive filesystem tools interrupt for
		// human approval. bailian_web_search is deliberately NOT gated — gating it
		// made every search interrupt, and each resume_sse resets the
		// ToolCallLimitHook counter, so the research loop never converged.
		this.humanInTheLoopHook = HumanInTheLoopHook.builder()
				.approvalOn("write_file", "The agent wants to write a file to disk. Approve?")
				.approvalOn("edit_file", "The agent wants to edit a file on disk. Approve?")
				.build();

		this.toolCallLimitHook = ToolCallLimitHook.builder()
				.runLimit(25)
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
				.enableLoopingLog(true)
				.build();
	}

	private static SubAgentSpec createWriterAgent(String subWriterPrompt) {
		return SubAgentSpec.builder()
				.name("writer-agent")
				.description("Dedicated report writer. Call ONCE after all research-agent tasks are " +
						"complete. It reads question.txt and all findings-*.md files and writes " +
						"final_report.md. Do not give it web-research instructions.")
				.systemPrompt(subWriterPrompt)
				.enableLoopingLog(true)
				.build();
	}

	private static SubAgentSpec createReviserAgent(String subReviserPrompt) {
		return SubAgentSpec.builder()
				.name("reviser-agent")
				.description("Dedicated report reviser. Call after critique-agent returns feedback. " +
						"It edits final_report.md in place according to the critique text passed in " +
						"the task description.")
				.systemPrompt(subReviserPrompt)
				.enableLoopingLog(true)
				.build();
	}

	private static ChatModel buildModel(DashScopeApi dashScopeApi, String modelName) {
		return DashScopeChatModel.builder()
				.dashScopeApi(dashScopeApi)
				.defaultOptions(DashScopeChatOptions.builder().model(modelName).build())
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
				1. Write the original user question to `question.txt` for reference.
				2. Plan: break the topic into 3-5 specific, independent sub-questions.
				3. Research: MANDATORY dispatch ALL sub-questions as separate `task` tool calls with
				   subagent_type `research-agent` in ONE single response so they run in parallel.
				   Never send just one task call per response. In each task description, assign a unique
				   findings file (`findings-1.md`, `findings-2.md`, ...) and require the researcher to
				   write its full findings there.
				4. Write: after ALL research tasks return, dispatch ONE `task` call with subagent_type
				   `writer-agent`, telling it to read `question.txt` and all `findings-*.md` files and
				   write the complete report to `final_report.md`.
				5. Review: dispatch ONE `task` call with subagent_type `critique-agent` to critique
				   `final_report.md`.
				6. Revise: pass the critique feedback verbatim to ONE `task` call with subagent_type
				   `reviser-agent`, which edits `final_report.md` in place.
				7. Repeat steps 5-6 at most ONE more time, then finish with a short conclusion.

				Keep your own messages short: plan, dispatch tasks, collect results, finish.
				All report formatting rules live in the writer-agent prompt; do not duplicate them.

				If the `writer-agent` task returns an exception or otherwise fails, do NOT write or edit
				`final_report.md` yourself. Retry the writer task once; if it fails again, stop and report
				the failure to the user instead of producing a fallback report.
				""";

		// Sub-agent prompt for research
		public static String subResearchPrompt = """
				You are a dedicated researcher. Your job is to conduct research based on the user's questions.

				Conduct thorough research. The task description names a findings file (e.g. `findings-1.md`) —
				write your complete, self-contained findings in Markdown to that file with `write_file`,
				including every source URL you used.

				Then reply to the orchestrator with a SHORT summary: the findings file path plus the key
				points in a few lines. The full content lives in the findings file.
				""";

		// Sub-agent prompt for critique
		public static String subCritiquePrompt = """
				You are a dedicated editor. You are being tasked to critique a report.
				
				You can find the report at `final_report.md`.
				You can find the question/topic for this report at `question.txt`.
				
				The user may ask for specific areas to critique the report in.
				Respond with a detailed critique of the report. Focus on areas that could be improved.
				
				You can use the search tool to search for information, if that will help you critique the report.
				
				Do not write to the `final_report.md` yourself.
				
				Things to check:
				- Each section is appropriately named and structured
				- The report is written in essay/textbook style - text heavy, not just bullet points
				- The report is comprehensive without missing important details
				- The article covers key areas ensuring overall understanding
				- The article deeply analyzes causes, impacts, and trends with valuable insights
				- The article closely follows the research topic and directly answers questions
				- The article has clear structure, fluent language, and is easy to understand
				""";

		// Sub-agent prompt for the dedicated report writer (pipeline stage 4)
		public static String subWriterPrompt = """
				You are a dedicated report writer. Do NOT search the web; your only inputs are files.

				**Tool-calling rule (CRITICAL):** Issue only ONE tool call per message. Never put two
				tool calls (for example `ls` and `read_file`) in the same response — do them one by one.

				1. Read `question.txt` to understand the research topic.
				2. Use `ls` to list the workspace, then read every `findings-*.md` file.
				3. Synthesize ALL findings into one polished Markdown report and write it to
				   `final_report.md` with `write_file`.

				**Report Format Requirements:**
				- CRITICAL: Write in the SAME language as the user's question!
				- Use clear Markdown with proper structure (# for title, ## for sections, ### for subsections)
				- Include specific facts and insights from the findings
				- Reference sources using [Title](URL) format
				- Provide balanced, thorough analysis
				- Be comprehensive - users expect detailed, in-depth answers
				- End with a "### Sources" section listing all references

				**Citation Rules:**
				- Assign each unique URL a single citation number [1], [2], etc.
				- Number sources sequentially without gaps in the final list
				- Each source should be a separate list item
				- Format: [1] Source Title: URL

				Structure the report appropriately for the question type:
				- Comparison: intro → overview A → overview B → comparison → conclusion
				- List: Simple numbered/bulleted list or separate sections per item
				- Overview/Summary: intro → concept 1 → concept 2 → ... → conclusion
				- Analysis: thesis → evidence → analysis → conclusion

				When done, reply with a one-line confirmation that final_report.md was written.
				""";

		// Sub-agent prompt for the dedicated reviser (pipeline stage 6)
		public static String subReviserPrompt = """
				You are a dedicated editor-reviser. The critique feedback for the report is included in
				your task description.

				1. Read `final_report.md` and `question.txt`.
				2. Apply the critique: fix structure, completeness, analysis depth and citations.
				   Use `edit_file` to revise `final_report.md` in place.
				3. Do NOT run new web research unless the critique points out a factual error;
				   your job is revising the existing text.

				Keep the report in the SAME language as the question.
				When done, reply with a short list of the revisions you applied.
				""";
	}
}
