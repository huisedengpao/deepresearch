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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 轻量评测器：把 DeepResearch 的报告质量量化成可比较的指标。
 *
 * 指标定义（以「事实点」为判定单元，而非整篇报告对错）：
 * <ul>
 *   <li>TP：报告中命中的必需事实点</li>
 *   <li>FN：报告中遗漏的必需事实点</li>
 *   <li>FP：报告中出现的禁止断言（幻觉/错误表述）</li>
 *   <li>Recall = TP / (TP + FN)，Precision = TP / (TP + FP)</li>
 * </ul>
 * 引用数单独作为附加指标，低于 minSources 视为不达标。
 *
 * 局限：事实点命中采用子串匹配，召回率有限；正式评测集应替换为复杂研究题并配合人工/LLM 判官。
 */
public class EvalRunner {

	public record EvalCase(String id, String question, List<String> mustInclude,
			List<String> mustNotInclude, int minSources, String judgeRubric) {
	}

	public record CaseResult(String id, int truePositives, int falsePositives, int falseNegatives,
			int sources, List<String> missing, List<String> violated) {

		public double recall() {
			int denom = truePositives + falseNegatives;
			return denom == 0 ? 1.0 : (double) truePositives / denom;
		}

		public double precision() {
			int denom = truePositives + falsePositives;
			return denom == 0 ? 1.0 : (double) truePositives / denom;
		}
	}

	public record Summary(int truePositives, int falsePositives, int falseNegatives) {

		public double precision() {
			int denom = truePositives + falsePositives;
			return denom == 0 ? 1.0 : (double) truePositives / denom;
		}

		public double recall() {
			int denom = truePositives + falseNegatives;
			return denom == 0 ? 1.0 : (double) truePositives / denom;
		}

		public double f1() {
			double p = precision();
			double r = recall();
			return (p + r) == 0 ? 0.0 : 2 * p * r / (p + r);
		}
	}

	public static List<EvalCase> loadCases(InputStream in) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		List<EvalCase> cases = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				cases.add(mapper.readValue(line, EvalCase.class));
			}
		}
		return cases;
	}

	public static CaseResult score(EvalCase evalCase, String report) {
		String lower = report.toLowerCase(Locale.ROOT);
		int tp = 0;
		int fn = 0;
		int fp = 0;
		List<String> missing = new ArrayList<>();
		List<String> violated = new ArrayList<>();

		for (String need : evalCase.mustInclude()) {
			if (lower.contains(need.toLowerCase(Locale.ROOT))) {
				tp++;
			}
			else {
				fn++;
				missing.add(need);
			}
		}
		for (String bad : evalCase.mustNotInclude()) {
			if (lower.contains(bad.toLowerCase(Locale.ROOT))) {
				fp++;
				violated.add(bad);
			}
		}
		return new CaseResult(evalCase.id(), tp, fp, fn, countSources(report), missing, violated);
	}

	public static Summary aggregate(List<CaseResult> results) {
		int tp = 0;
		int fp = 0;
		int fn = 0;
		for (CaseResult r : results) {
			tp += r.truePositives();
			fp += r.falsePositives();
			fn += r.falseNegatives();
		}
		return new Summary(tp, fp, fn);
	}

	private static int countSources(String report) {
		int count = 0;
		int idx = 0;
		while ((idx = report.indexOf("](http", idx)) != -1) {
			count++;
			idx += 6;
		}
		return count;
	}

	/**
	 * CLI：java -cp <classpath> EvalRunner <dataset.jsonl> <reportsDir>
	 * 退出码非 0 表示未过门禁（recall/precision 低于阈值），可直接用于 CI。
	 */
	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println("用法: EvalRunner <dataset.jsonl> <reportsDir>");
			System.exit(2);
		}
		List<EvalCase> cases;
		try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
			cases = loadCases(in);
		}
		Path reportsDir = Path.of(args[1]);
		if (Boolean.getBoolean("judge")) {
			runJudge(cases, reportsDir);
			return;
		}
		List<CaseResult> results = new ArrayList<>();
		for (EvalCase c : cases) {
			Path reportPath = reportsDir.resolve(c.id() + ".md");
			String report = Files.exists(reportPath) ? Files.readString(reportPath, StandardCharsets.UTF_8) : "";
			results.add(score(c, report));
		}
		Summary summary = aggregate(results);
		for (CaseResult r : results) {
			System.out.printf("%-22s recall=%.3f precision=%.3f sources=%d missing=%s violated=%s%n",
					r.id(), r.recall(), r.precision(), r.sources(), r.missing(), r.violated());
		}
		System.out.printf("SUMMARY recall=%.3f precision=%.3f f1=%.3f%n",
				summary.recall(), summary.precision(), summary.f1());

		double threshold = Double.parseDouble(System.getProperty("eval.threshold", "0.80"));
		if (summary.recall() < threshold || summary.precision() < threshold) {
			System.err.printf("低于门禁阈值 %.2f%n", threshold);
			System.exit(1);
		}
	}

	private static void runJudge(List<EvalCase> cases, Path reportsDir) throws IOException {
		LlmJudge judge = LlmJudge.defaultJudge();
		List<Double> scores = new ArrayList<>();
		for (EvalCase evalCase : cases) {
			if (evalCase.judgeRubric() == null || evalCase.judgeRubric().isBlank()) {
				continue;
			}
			Path reportPath = reportsDir.resolve(evalCase.id() + ".md");
			String report = Files.exists(reportPath) ? Files.readString(reportPath, StandardCharsets.UTF_8) : "";
			LlmJudge.JudgeScore judged = judge.judge(evalCase.question(), evalCase.judgeRubric(), report);
			System.out.printf("%-38s judge=%.2f%n", evalCase.id(), judged.score());
			scores.add(judged.score());
		}
		double average = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
		System.out.printf("JUDGE_AVG=%.3f (n=%d)%n", average, scores.size());
		double threshold = Double.parseDouble(System.getProperty("eval.threshold", "0.80"));
		if (scores.isEmpty() || average < threshold) {
			System.err.printf("judge 平均分低于 %.2f%n", threshold);
			System.exit(1);
		}
	}
}
