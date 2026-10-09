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
package io.gravitee.singularitee.inference.decision;

import io.gravitee.jinja4j.Environment;
import io.gravitee.singularitee.inference.api.template.ChatTemplateRenderer;
import io.gravitee.singularitee.inference.api.textgen.ChatMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A minimal {@link ChatTemplateRenderer} over jinja4j, for tests. */
final class JinjaRenderer implements ChatTemplateRenderer {

  private final Environment env = new Environment();

  JinjaRenderer() {
    env.setAutoEscaping(false);
  }

  @Override
  public String render(
    String templateString,
    List<ChatMessage> messages,
    List<Map<String, Object>> tools,
    boolean addGenerationPrompt,
    Map<String, Object> extraVariables
  ) {
    var ctx = new LinkedHashMap<String, Object>();
    if (extraVariables != null) ctx.putAll(extraVariables);
    if (messages != null) {
      ctx.put(
        "messages",
        messages
          .stream()
          .map(m -> Map.of("role", m.role().getLabel(), "content", m.content()))
          .toList()
      );
    }
    ctx.put("add_generation_prompt", addGenerationPrompt);
    return env.fromString(templateString, "<test>").render(ctx);
  }
}
