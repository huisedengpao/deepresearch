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
package com.alibaba.cloud.ai.examples.deepresearch.eval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测流水线自检：既验证「优质报告过门禁」，也验证「劣质报告会被指标识别」。
 * 这是 CI 门禁的最小实现——真实 agent 产物替换 reports 目录后即可对模型输出评分。
 */
class EvalRunnerTest {

	// 仅这 5 条配有 fixture 报告，用于验证流水线本身；其余评测题等真实 agent 产物到位后评分。
	private static final List<String> FIXTURE_IDS = List.of(
			"mqtt-port", "springboot3-jakarta", "http-429", "sql-acid", "redis-zset");

	@Test
	void datasetShouldCoverAtLeast20Cases() throws IOException {
		List<EvalRunner.EvalCase> cases;
		try (InputStream in = classpath("evals/dataset.jsonl")) {
			cases = EvalRunner.loadCases(in);
		}
		assertTrue(cases.size() >= 20, "评测集应至少 20 条，当前: " + cases.size());
		assertTrue(cases.stream().anyMatch(c -> c.id().equals("mqtt-port")), "缺少 fixture 用例");
	}

	@Test
	void qualityReportsShouldPassGate() throws IOException {
		List<EvalRunner.EvalCase> cases;
		try (InputStream in = classpath("evals/dataset.jsonl")) {
			cases = EvalRunner.loadCases(in);
		}
		List<EvalRunner.CaseResult> results = new ArrayList<>();
		for (EvalRunner.EvalCase evalCase : cases) {
			if (!FIXTURE_IDS.contains(evalCase.id())) {
				continue;
			}
			String report = readResource("evals/reports/" + evalCase.id() + ".md");
			EvalRunner.CaseResult result = EvalRunner.score(evalCase, report);
			assertTrue(result.sources() >= evalCase.minSources(),
					evalCase.id() + " 引用数不足: " + result.sources());
			results.add(result);
		}
		EvalRunner.Summary summary = EvalRunner.aggregate(results);
		System.out.printf("[eval] recall=%.3f precision=%.3f f1=%.3f%n",
				summary.recall(), summary.precision(), summary.f1());
		assertTrue(summary.recall() >= 0.95, "recall 未过门禁");
		assertTrue(summary.precision() >= 0.95, "precision 未过门禁");
	}

	@Test
	void degradedReportShouldBePenalized() {
		EvalRunner.EvalCase mqtt = new EvalRunner.EvalCase(
				"mqtt-port", "MQTT 默认端口", List.of("1883"), List.of("1884"), 1, null);
		EvalRunner.CaseResult result = EvalRunner.score(mqtt, "MQTT 使用端口 1884 传输。");

		assertEquals(0, result.truePositives());
		assertEquals(1, result.falsePositives());
		assertEquals(1, result.falseNegatives());
		assertEquals(0.0, result.recall(), 1e-9);
		assertEquals(0.0, result.precision(), 1e-9);
	}

	@Test
	void judgeParsesScoreFromModelOutput() {
		ChatModel model = mock(ChatModel.class);
		String json = "{\"score\": 0.75, \"rationale\": \"四点覆盖完整\"}";
		ChatResponse response = new ChatResponse(
				List.of(new Generation(new AssistantMessage(json))));
		when(model.call(any(Prompt.class))).thenReturn(response);

		LlmJudge.JudgeScore judged = new LlmJudge(model).judge("q", "rubric", "report");
		assertEquals(0.75, judged.score(), 1e-9);
	}

	@Test
	void llmJudgeSmoke() throws IOException {
		assumeTrue(System.getenv("AI_DASHSCOPE_API_KEY") != null,
				"未设置 AI_DASHSCOPE_API_KEY，跳过真实 LLM judge 调用");

		String rubric = "评分需覆盖：1) 两者的编排抽象；2) 至少 2 处具体设计差异；3) 适用场景结论；4) 引用来源。四项全有得满分，缺一项扣 0.25。";
		EvalRunner.EvalCase evalCase = new EvalRunner.EvalCase(
				"compare-spring-ai-alibaba-langchain4j",
				"对比 Spring AI Alibaba 与 LangChain4j 在 Agent 编排上的设计差异。",
				List.of(), List.of(), 1, rubric);
		String report = readResource("evals/reports/" + evalCase.id() + ".md");

		String badReport = """
				# Spring AI Alibaba 与 LangChain4j

				两者都是 Java 生态里的 AI 框架，都能用来开发 Agent 应用。
				Spring AI Alibaba 来自阿里巴巴，LangChain4j 来自开源社区。总体上都很好用。
				""";

		try {
			LlmJudge judge = LlmJudge.defaultJudge();
			LlmJudge.JudgeScore good = judge.judge(evalCase.question(), rubric, report);
			LlmJudge.JudgeScore bad = judge.judge(evalCase.question(), rubric, badReport);
			System.out.printf("[judge] good=%.2f bad=%.2f%n", good.score(), bad.score());
			System.out.println("[judge-bad] " + bad.rationale());
			assertTrue(good.score() >= 0.70, "覆盖四点 rubric 的报告应拿到高分, 实际=" + good.score());
			assertTrue(bad.score() <= 0.30, "劣质报告应被打低分, 实际=" + bad.score());
			assertTrue(good.score() - bad.score() >= 0.40,
					"judge 判别力不足: good=" + good.score() + " bad=" + bad.score());
		}
		catch (Exception ex) {
			// 账号欠费/网络不可用时跳过，避免 CI 因外部依赖失败
			assumeTrue(false, "真实 judge 调用不可用: " + ex.getMessage());
		}
	}

	@Test
	void judgeRealAgentReport() throws IOException {
		assumeTrue(System.getenv("AI_DASHSCOPE_API_KEY") != null,
				"未设置 AI_DASHSCOPE_API_KEY，跳过真实 judge 调用");
		InputStream probe = EvalRunnerTest.class.getClassLoader()
				.getResourceAsStream("evals/reports/compare-spring-ai-alibaba-langchain4j-agent.md");
		assumeTrue(probe != null, "缺少真 agent 报告，跳过");
		String report = readResource("evals/reports/compare-spring-ai-alibaba-langchain4j-agent.md");
		String rubric = "评分需覆盖：1) 两者的编排抽象；2) 至少 2 处具体设计差异；3) 适用场景结论；4) 引用来源。四项全有得满分，缺一项扣 0.25。";
		try {
			LlmJudge.JudgeScore judged = LlmJudge.defaultJudge()
					.judge("对比 Spring AI Alibaba 与 LangChain4j 在 Agent 编排上的设计差异。", rubric, report);
			System.out.printf("[judge-real] score=%.2f%n", judged.score());
			assertTrue(judged.score() >= 0.0 && judged.score() <= 1.0, "judge 分数越界");
		}
		catch (Exception ex) {
			assumeTrue(false, "真实 judge 调用不可用: " + ex.getMessage());
		}
	}

	private static InputStream classpath(String name) {
		InputStream in = EvalRunnerTest.class.getClassLoader().getResourceAsStream(name);
		assertNotNull(in, "缺少 classpath 资源: " + name);
		return in;
	}

	private static String readResource(String name) throws IOException {
		try (InputStream in = classpath(name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
