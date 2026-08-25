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
package io.gravitee.singularitee.engine.template;

import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateContextHelper;
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateRenderer;
import io.gravitee.singularitee.inference.api.template.ChatTemplateRenderer;
import io.gravitee.singularitee.inference.api.textgen.AudioContent;
import io.gravitee.singularitee.inference.api.textgen.ChatMessage;
import io.gravitee.singularitee.inference.api.textgen.Content;
import io.gravitee.singularitee.inference.api.textgen.ImageContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ChatTemplateRenderer} that flattens chat messages into the Jinja
 * variable shape a model's chat template expects, then delegates the actual
 * rendering to a {@link TemplateRenderer}.
 *
 * <p>It owns no templating engine of its own: the single {@link TemplateRenderer}
 * it composes holds the one environment and compiled-template cache. This class
 * adds only the message and tool flattening the chat-template contract requires.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DelegatingChatTemplateRenderer implements ChatTemplateRenderer {

  private static final Logger LOGGER = LoggerFactory.getLogger(
    DelegatingChatTemplateRenderer.class
  );

  private final TemplateRenderer renderer;

  /** Renders through the given {@link TemplateRenderer}, which owns the environment and cache. */
  public DelegatingChatTemplateRenderer(TemplateRenderer renderer) {
    this.renderer = renderer;
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

    // Defaults
    ctx.put("bos_token", "");
    ctx.put("eos_token", "");

    // Extra variables override defaults (bos_token, eos_token, ...) and can also
    // supply messages/tools/add_generation_prompt themselves. Explicit method
    // parameters below only override these when they are non-null, so callers
    // that already built the Jinja-shaped message maps can pass them via
    // extraVariables without being replaced by an empty list.
    if (extraVariables != null) {
      ctx.putAll(extraVariables);
    }

    // Core template variables: only set when explicitly provided so that
    // values supplied via extraVariables are not clobbered.
    if (messages != null) {
      ctx.put("messages", toMessageMaps(messages));
    }
    ctx.put("add_generation_prompt", addGenerationPrompt);

    if (tools != null && !tools.isEmpty()) {
      ctx.put("tools", tools);
    }

    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Chat template render, context:\n{}", TemplateContextHelper.dump(ctx, 200));
    }

    String rendered = renderer.render(templateString, "<chat_template>", ctx);

    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Chat template render, output ({} chars):\n{}", rendered.length(), rendered);
    }
    return rendered;
  }

  // Message conversion

  private static List<Map<String, Object>> toMessageMaps(List<ChatMessage> messages) {
    if (messages == null) return List.of();
    return messages.stream().map(DelegatingChatTemplateRenderer::toMessageMap).toList();
  }

  private static Map<String, Object> toMessageMap(ChatMessage msg) {
    var map = new LinkedHashMap<String, Object>();
    map.put("role", msg.role().getLabel());

    if (msg.hasMedia()) {
      // Multimodal: content is a list of typed parts
      map.put("content", toContentParts(msg));
    } else {
      map.put("content", msg.content());
    }

    return map;
  }

  private static List<Map<String, Object>> toContentParts(ChatMessage msg) {
    List<Map<String, Object>> parts = new ArrayList<>();
    if (msg.hasText()) {
      parts.add(Map.of("type", "text", "text", msg.content()));
    }
    for (Content content : msg.media()) {
      if (content instanceof ImageContent) {
        parts.add(Map.of("type", "image"));
      } else if (content instanceof AudioContent) {
        parts.add(Map.of("type", "audio"));
      }
    }
    return parts;
  }
}
