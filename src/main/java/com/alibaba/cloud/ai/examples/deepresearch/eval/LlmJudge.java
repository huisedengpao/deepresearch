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

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-judge：用于开放式研究题，按 rubric 对报告打分（0~1）。
 * 与 EvalRunner 的「事实点匹配」互补：前者判语义质量，后者判硬事实命中。
 */
public class LlmJudge {

	public record JudgeScore(double score, String rationale) {
	}

	private static final Pattern SCORE = Pattern.compile("\"score\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

	private final ChatModel chatModel;

	public LlmJudge(ChatModel chatModel) {
		this.chatModel = chatModel;
	}

	public static LlmJudge defaultJudge() {
		String apiKey = System.getenv("AI_DASHSCOPE_API_KEY");
		DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(apiKey).build();
		ChatModel model = DashScopeChatModel.builder()
				.dashScopeApi(dashScopeApi)
				.defaultOptions(DashScopeChatOptions.builder().model("qwen-plus").build())
				.build();
		return new LlmJudge(model);
	}

	public JudgeScore judge(String question, String rubric, String report) {
		String prompt = """
				你是严格的研究报告评测员。请逐项核对评分标准：某一项若未被报告明确覆盖，该项一律记 0 分，
				不要因为报告"客套、看起来像回答了"而给分。空泛、无具体信息的表述不算覆盖。

				评分标准：
				%s

				研究问题：
				%s

				待评报告：
				%s

				先逐项判断每一项是否被明确覆盖（covered: true/false）并给一句理由，再按评分标准算总分。
				只输出一行 JSON，格式为：
				{"items":[{"id":1,"covered":true,"why":"..."},...],"score":0 到 1 之间的小数,"rationale":"一句话理由"}
				""".formatted(rubric, question, report);
		ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
		String content = response.getResult().getOutput().getText();
		return new JudgeScore(parseScore(content), content);
	}

	private static double parseScore(String text) {
		Matcher matcher = SCORE.matcher(text);
		if (matcher.find()) {
			double value = Double.parseDouble(matcher.group(1));
			return Math.max(0.0, Math.min(1.0, value));
		}
		return 0.0;
	}
}
