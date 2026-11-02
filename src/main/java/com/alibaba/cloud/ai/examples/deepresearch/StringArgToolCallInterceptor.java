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

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Normalizes tool calls whose single parameter is a plain String.
 *
 * <p>{@code ls} and {@code glob} are declared as {@code BiFunction<String, ToolContext, String>},
 * so their argument must be a JSON string literal. When the model emits a JSON object instead
 * (e.g. {@code {"path": "/workspace"}}), two things break: the local converter fails
 * ("Conversion from JSON to java.lang.String failed") and DashScope rejects the whole request
 * with HTTP 400, which makes the thread unrecoverable. This interceptor rewrites such arguments
 * into a valid JSON string literal before the tool runs.
 */
public class StringArgToolCallInterceptor extends ModelInterceptor {

	// Tools whose parameter type is a bare String rather than an object.
	private static final Set<String> STRING_ARG_TOOLS = Set.of("ls", "glob");

	private static final List<String> PATH_FIELDS = List.of("path", "directory", "dir", "pattern");

	private static final String DEFAULT_DIRECTORY = "/workspace";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Override
	public String getName() {
		return "stringArgToolCall";
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		ModelResponse response = handler.call(request);
		Object message = response.getMessage();
		if (!(message instanceof AssistantMessage assistant) || !assistant.hasToolCalls()) {
			return response;
		}

		List<AssistantMessage.ToolCall> rewritten = new ArrayList<>(assistant.getToolCalls().size());
		boolean changed = false;
		for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
			if (STRING_ARG_TOOLS.contains(call.name()) && !isJsonStringLiteral(call.arguments())) {
				rewritten.add(new AssistantMessage.ToolCall(call.id(), call.type(), call.name(),
						toJsonStringLiteral(call.arguments())));
				changed = true;
			}
			else {
				rewritten.add(call);
			}
		}
		if (!changed) {
			return response;
		}

		AssistantMessage patched = AssistantMessage.builder()
				.content(assistant.getText())
				.toolCalls(rewritten)
				.build();
		return ModelResponse.of(patched, response.getChatResponse());
	}

	/** True when the argument is already a JSON string literal, e.g. {@code "/workspace"}. */
	private static boolean isJsonStringLiteral(String arguments) {
		if (arguments == null || arguments.isBlank()) {
			return false;
		}
		String trimmed = arguments.trim();
		return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"");
	}

	/**
	 * Collects the path out of an object-shaped argument, or falls back to a bare
	 * path / default directory, and returns it as a JSON string literal.
	 */
	private static String toJsonStringLiteral(String arguments) {
		if (arguments == null || arguments.isBlank()) {
			return jsonString(DEFAULT_DIRECTORY);
		}
		String trimmed = arguments.trim();
		if (trimmed.startsWith("{")) {
			try {
				JsonNode node = MAPPER.readTree(trimmed);
				for (String field : PATH_FIELDS) {
					JsonNode value = node.get(field);
					if (value != null && value.isTextual()) {
						return jsonString(value.asText());
					}
				}
				for (JsonNode value : node) {
					if (value.isTextual()) {
						return jsonString(value.asText());
					}
				}
			}
			catch (Exception ignored) {
				// malformed object: fall through to the default
			}
			return jsonString(DEFAULT_DIRECTORY);
		}
		return jsonString(trimmed);
	}

	private static String jsonString(String value) {
		try {
			return MAPPER.writeValueAsString(value);
		}
		catch (Exception e) {
			return "\"" + value.replace("\"", "\\\"") + "\"";
		}
	}

}
