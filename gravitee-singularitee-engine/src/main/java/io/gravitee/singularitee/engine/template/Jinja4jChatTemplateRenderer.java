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

import io.gravitee.jinja4j.Environment;
import io.gravitee.jinja4j.Template;
import io.gravitee.singularitee.inference.api.template.ChatTemplateRenderer;
import io.gravitee.singularitee.inference.api.textgen.AudioContent;
import io.gravitee.singularitee.inference.api.textgen.ChatMessage;
import io.gravitee.singularitee.inference.api.textgen.Content;
import io.gravitee.singularitee.inference.api.textgen.ImageContent;
import io.gravitee.singularitee.pipeline.executor.JinjaContextHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ChatTemplateRenderer} backed by <b>Jinja4j</b>, a pure-Java Jinja2 engine.
 *
 * <p>No Python interpreter, no native code, no FFI — works everywhere the JVM runs.
 * This is the default renderer used by Singularitee pipeline executors.
 *
 * <p>The {@link ChatTemplateRenderer} interface is defined in
 * {@code gravitee-inference-api}; the implementation lives here so that the
 * inference API artifact stays a pure-contract JAR with no Jinja4j transitive
 * dependency on its consumers.
 *
 * <p>Templates are compiled once and cached by their source string. The Jinja4j
 * {@link Environment} is thread-safe; compiled {@link Template} instances are
 * immutable and safe to share across threads.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class Jinja4jChatTemplateRenderer implements ChatTemplateRenderer {

  private static final Logger LOGGER = LoggerFactory.getLogger(Jinja4jChatTemplateRenderer.class);

  private final Environment env;
  private final ConcurrentHashMap<String, Template> cache = new ConcurrentHashMap<>();

  /**
   * Creates a renderer with a default Jinja4j environment.
   * Auto-escaping is OFF (required for LLM chat templates).
   */
  public Jinja4jChatTemplateRenderer() {
    this(defaultEnvironment());
  }

  /**
   * Creates a renderer with a pre-configured environment.
   *
   * <p><b>Caller contract:</b> the supplied {@link Environment} must have
   * auto-escaping disabled. LLM chat templates produce raw text (special tokens,
   * markup) that must never be HTML-escaped. The renderer does <i>not</i> mutate
   * the caller-owned environment — use {@link #defaultEnvironment()} as a
   * starting point if you are unsure.
   */
  public Jinja4jChatTemplateRenderer(Environment env) {
    this.env = env;
  }

  /**
   * Builds a fully-configured {@link Environment} suitable for LLM chat templates
   * (auto-escaping off). Exposed so callers of the single-arg constructor can
   * start from a safe baseline and layer their own customizations.
   */
  public static Environment defaultEnvironment() {
    Environment e = new Environment();
    e.setAutoEscaping(false);
    return e;
  }

  /**
   * Renders a template whose {@code messages}/{@code tools} were already placed
   * in {@code variables} by the caller — nothing outside the variable map is
   * injected, so pre-sanitised entries (special-token-escaped tools) cannot be
   * clobbered by raw parameters.
   */
  public String renderFromVariables(String templateString, Map<String, Object> variables) {
    return render(templateString, null, null, true, variables);
  }

  @Override
  public String render(
    String templateString,
    List<ChatMessage> messages,
    List<Map<String, Object>> tools,
    boolean addGenerationPrompt,
    Map<String, Object> extraVariables
  ) {
    Template template = cache.computeIfAbsent(templateString, src ->
      env.fromString(src, "<chat_template>")
    );

    var ctx = new LinkedHashMap<String, Object>();

    // Defaults
    ctx.put("bos_token", "");
    ctx.put("eos_token", "");

    // Extra variables override defaults (bos_token, eos_token, …) and can also
    // supply messages/tools/add_generation_prompt themselves. Explicit method
    // parameters below only override these when they are non-null — this lets
    // callers that already built the Jinja-shaped message maps pass them via
    // extraVariables without being silently replaced by an empty list.
    if (extraVariables != null) {
      ctx.putAll(extraVariables);
    }

    // Core template variables — only set when explicitly provided so that
    // values supplied via extraVariables are not clobbered.
    if (messages != null) {
      ctx.put("messages", toMessageMaps(messages));
    }
    ctx.put("add_generation_prompt", addGenerationPrompt);

    if (tools != null && !tools.isEmpty()) {
      ctx.put("tools", tools);
    }

    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Jinja4j render — context:\n{}", JinjaContextHelper.dump(ctx, 200));
    }

    String rendered = template.render(ctx);

    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Jinja4j render — output ({} chars):\n{}", rendered.length(), rendered);
    }
    return rendered;
  }

  // ── Message conversion ─────────────────────────────────────────────────

  private static List<Map<String, Object>> toMessageMaps(List<ChatMessage> messages) {
    if (messages == null) return List.of();
    return messages.stream().map(Jinja4jChatTemplateRenderer::toMessageMap).toList();
  }

  private static Map<String, Object> toMessageMap(ChatMessage msg) {
    var map = new LinkedHashMap<String, Object>();
    map.put("role", msg.role().getLabel());

    if (msg.hasMedia()) {
      // Multimodal: content is a list of typed parts (OpenAI format)
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
