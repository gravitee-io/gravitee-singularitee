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
package io.gravitee.singularitee.inference.vllm;

import io.gravitee.singularitee.inference.api.Constants;
import io.gravitee.singularitee.inference.api.textgen.GenerationRequest;
import io.gravitee.singularitee.inference.api.textgen.PayloadParser;
import io.gravitee.singularitee.inference.api.textgen.TagConfig;
import java.util.List;
import java.util.Map;

/**
 * Generation request for the vLLM engine.
 *
 * <p>Implements {@link GenerationRequest} and adds the reasoning and tool-call
 * tag configuration plus optional LoRA selection. {@code prompt} must already
 * be rendered; the adapter does not apply a chat template. Every field may be
 * {@code null}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record VllmRequest(
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
  List<Map<String, Object>> tools,
  String loraName,
  String loraPath
) implements GenerationRequest {
  /**
   * Builds a request from a raw payload map; tag configs are left {@code null}
   * for the caller to set.
   */
  @SuppressWarnings("unchecked")
  public VllmRequest(Map<String, Object> payload) {
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
      null,
      null,
      PayloadParser.parseTools(payload.get("tools")),
      PayloadParser.stringValue(payload.get("loraName")),
      PayloadParser.stringValue(payload.get("loraPath"))
    );
  }

  /** Whether chat messages (and therefore possible media) were supplied. */
  public boolean hasMessages() {
    return messages != null && !messages.isEmpty();
  }

  /** Whether tool definitions were supplied. */
  public boolean hasTools() {
    return tools != null && !tools.isEmpty();
  }

  /** Whether a LoRA adapter path was supplied. */
  public boolean hasLora() {
    return loraPath != null && !loraPath.isBlank();
  }
}
