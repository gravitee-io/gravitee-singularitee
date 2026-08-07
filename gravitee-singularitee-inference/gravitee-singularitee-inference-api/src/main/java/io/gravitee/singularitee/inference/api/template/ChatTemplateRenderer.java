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
package io.gravitee.singularitee.inference.api.template;

import io.gravitee.singularitee.inference.api.textgen.ChatMessage;
import java.util.List;
import java.util.Map;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface ChatTemplateRenderer {
  /**
   * <p>{@code messages} and {@code tools} are only set when non-null — otherwise
   * whatever the caller supplied via {@code extraVariables} is kept.
   *
   * @param templateString      the raw template
   * @param messages            conversation messages, or {@code null} if supplied via extraVariables
   * @param tools               tool definitions in OpenAI format, or {@code null}
   * @param addGenerationPrompt whether to append the assistant generation prompt
   * @param extraVariables      additional template variables, or {@code null}
   * @return the rendered prompt string
   */
  String render(
    String templateString,
    List<ChatMessage> messages,
    List<Map<String, Object>> tools,
    boolean addGenerationPrompt,
    Map<String, Object> extraVariables
  );

  /**
   * Convenience overload without extra variables.
   */
  default String render(
    String templateString,
    List<ChatMessage> messages,
    List<Map<String, Object>> tools,
    boolean addGenerationPrompt
  ) {
    return render(templateString, messages, tools, addGenerationPrompt, null);
  }
}
