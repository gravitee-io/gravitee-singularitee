/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.singularitee.engine.api.pipeline.executor;

import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;

/**
 * OpenInference OpenTelemetry semantic-convention keys and span kinds, emitted verbatim
 * on our spans so OpenInference-aware trace tools render Singularitee traces
 * as first-class LLM/agent traces. These keys are a published contract: they are never prefixed.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class OpenInference {

  private OpenInference() {}

  // Span kind
  public static final String SPAN_KIND = "openinference.span.kind";
  public static final String KIND_LLM = "LLM";
  public static final String KIND_CHAIN = "CHAIN";
  public static final String KIND_EMBEDDING = "EMBEDDING";
  public static final String KIND_GUARDRAIL = "GUARDRAIL";
  public static final String KIND_RERANKER = "RERANKER";
  public static final String KIND_TOOL = "TOOL";
  public static final String KIND_AGENT = "AGENT";

  // Common
  public static final String INPUT_VALUE = "input.value";
  public static final String INPUT_MIME = "input.mime_type";
  public static final String OUTPUT_VALUE = "output.value";
  public static final String OUTPUT_MIME = "output.mime_type";
  public static final String SESSION_ID = "session.id";
  public static final String METADATA = "metadata";
  public static final String MIME_TEXT = "text/plain";
  public static final String MIME_JSON = "application/json";

  // LLM
  public static final String LLM_MODEL_NAME = "llm.model_name";
  public static final String LLM_PROVIDER = "llm.provider";
  public static final String LLM_INVOCATION_PARAMETERS = "llm.invocation_parameters";
  public static final String LLM_TOKEN_PROMPT = "llm.token_count.prompt";
  public static final String LLM_TOKEN_COMPLETION = "llm.token_count.completion";
  public static final String LLM_TOKEN_TOTAL = "llm.token_count.total";
  public static final String LLM_TOKEN_REASONING = "llm.token_count.completion_details.reasoning";

  // Embedding
  public static final String EMBEDDING_MODEL_NAME = "embedding.model_name";
  public static final String EMBEDDING_TEXT = "embedding.text";

  /** {@code llm.input_messages.<i>.message.role}. */
  public static String inputMessageRole(int i) {
    return "llm.input_messages." + i + ".message.role";
  }

  /** {@code llm.input_messages.<i>.message.content}. */
  public static String inputMessageContent(int i) {
    return "llm.input_messages." + i + ".message.content";
  }

  /** {@code llm.input_messages.<i>.message.tool_call_id} (a tool-result turn answers this call). */
  public static String inputMessageToolCallId(int i) {
    return "llm.input_messages." + i + ".message.tool_call_id";
  }

  /** {@code llm.input_messages.<i>.message.tool_calls.<j>.tool_call.id}. */
  public static String inputToolCallId(int i, int j) {
    return "llm.input_messages." + i + ".message.tool_calls." + j + ".tool_call.id";
  }

  /** {@code llm.input_messages.<i>.message.tool_calls.<j>.tool_call.function.name}. */
  public static String inputToolCallName(int i, int j) {
    return "llm.input_messages." + i + ".message.tool_calls." + j + ".tool_call.function.name";
  }

  /** {@code llm.input_messages.<i>.message.tool_calls.<j>.tool_call.function.arguments}. */
  public static String inputToolCallArguments(int i, int j) {
    return "llm.input_messages." + i + ".message.tool_calls." + j + ".tool_call.function.arguments";
  }

  /** {@code llm.output_messages.<i>.message.role}. */
  public static String outputMessageRole(int i) {
    return "llm.output_messages." + i + ".message.role";
  }

  /** {@code llm.output_messages.<i>.message.content}. */
  public static String outputMessageContent(int i) {
    return "llm.output_messages." + i + ".message.content";
  }

  /** A {@code message_content.type}: reasoning/thinking content, per OpenInference. */
  public static final String CONTENT_TYPE_REASONING = "reasoning";
  /** A {@code message_content.type}: plain text content. */
  public static final String CONTENT_TYPE_TEXT = "text";

  /** {@code llm.output_messages.<i>.message.contents.<j>.message_content.type}. */
  public static String outputContentType(int i, int j) {
    return "llm.output_messages." + i + ".message.contents." + j + ".message_content.type";
  }

  /** {@code llm.output_messages.<i>.message.contents.<j>.message_content.text}. */
  public static String outputContentText(int i, int j) {
    return "llm.output_messages." + i + ".message.contents." + j + ".message_content.text";
  }

  /** {@code llm.output_messages.<i>.message.tool_calls.<j>.tool_call.function.name}. */
  public static String outputToolCallName(int i, int j) {
    return "llm.output_messages." + i + ".message.tool_calls." + j + ".tool_call.function.name";
  }

  /** {@code llm.output_messages.<i>.message.tool_calls.<j>.tool_call.function.arguments}. */
  public static String outputToolCallArguments(int i, int j) {
    return (
      "llm.output_messages." + i + ".message.tool_calls." + j + ".tool_call.function.arguments"
    );
  }

  /** The OpenInference span kind for a core step type; {@link #KIND_CHAIN} for anything else. */
  public static String spanKind(String stepType) {
    if (stepType == null) {
      return KIND_CHAIN;
    }
    return switch (stepType) {
      case StepTypes.INFER -> KIND_LLM;
      case StepTypes.EMBED -> KIND_EMBEDDING;
      case StepTypes.GUARD, StepTypes.LLM_GUARD, StepTypes.REGEX_GUARD -> KIND_GUARDRAIL;
      case StepTypes.TOOL_SELECT, StepTypes.TODO -> KIND_TOOL;
      default -> KIND_CHAIN;
    };
  }
}
