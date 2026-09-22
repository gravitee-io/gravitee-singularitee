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
package io.gravitee.singularitee.inference.llama.cpp;

import io.gravitee.singularitee.inference.api.Constants;
import io.gravitee.singularitee.inference.api.textgen.GenerationRequest;
import io.gravitee.singularitee.inference.api.textgen.PayloadParser;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.inference.api.textgen.TagConfig;
import java.util.List;
import java.util.Map;

/**
 * Generation request for the llama.cpp engine.
 *
 * <p>Either {@code prompt} (already rendered) or {@code messages} (rendered with the model's
 * native chat template) drives generation; a non-blank prompt wins. Every sampling field is
 * nullable and falls back to the engine default. {@code reasoningTags} and {@code toolTags}
 * classify generated tokens into channels. {@code structuredOutput} constrains decoding with a
 * grammar; {@code null} leaves the text free.
 */
public record Request(
  String prompt,
  List<io.gravitee.singularitee.inference.api.textgen.ChatMessage> messages,
  Integer maxTokens,
  Float temperature,
  Float topP,
  Float presencePenalty,
  Float frequencyPenalty,
  List<String> stop,
  Integer seed,
  TagConfig reasoningTags,
  TagConfig toolTags,
  Integer topLogprobs,
  StructuredOutput structuredOutput
) implements GenerationRequest {
  /** Compatibility constructor for callers with no logprobs collection. */
  public Request(
    String prompt,
    List<io.gravitee.singularitee.inference.api.textgen.ChatMessage> messages,
    Integer maxTokens,
    Float temperature,
    Float topP,
    Float presencePenalty,
    Float frequencyPenalty,
    List<String> stop,
    Integer seed,
    TagConfig reasoningTags,
    TagConfig toolTags
  ) {
    this(
      prompt,
      messages,
      maxTokens,
      temperature,
      topP,
      presencePenalty,
      frequencyPenalty,
      stop,
      seed,
      reasoningTags,
      toolTags,
      null,
      null
    );
  }

  /** Builds a request from a raw payload map keyed by {@link Constants}; tags stay unset. */
  public Request(Map<String, Object> payload) {
    this(
      PayloadParser.stringValue(payload.get(Constants.PROMPT)),
      PayloadParser.parseMessages(payload.get(Constants.MESSAGES)),
      PayloadParser.intValue(payload.get(Constants.MAX_TOKENS)),
      PayloadParser.floatValue(payload.get(Constants.TEMPERATURE)),
      PayloadParser.floatValue(payload.get(Constants.TOP_P)),
      PayloadParser.floatValue(payload.get(Constants.PRESENCE_PENALTY)),
      PayloadParser.floatValue(payload.get(Constants.FREQUENCY_PENALTY)),
      PayloadParser.parseStop(payload.get(Constants.STOP)),
      PayloadParser.intValue(payload.get(Constants.SEED)),
      null, // reasoningTags
      null, // toolTags
      PayloadParser.intValue(payload.get(Constants.TOP_LOGPROBS)),
      null // structuredOutput
    );
  }

  /** Whether the request carries at least one chat message. */
  public boolean hasMessages() {
    return messages != null && !messages.isEmpty();
  }
}
