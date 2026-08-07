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
package io.gravitee.singularitee.pipeline.executor;

import io.gravitee.jinja4j.Environment;
import io.gravitee.jinja4j.Template;
import io.gravitee.singularitee.engine.template.Jinja4jChatTemplateRenderer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single rendering service shared by every pipeline step executor.
 *
 * <p>Owns the two process-lifetime Jinja4j resources — the {@link Environment}
 * and the compiled-{@link Template} cache — so that executors don't each need
 * their own copy. Template strings are keyed by their source so compiling the
 * same template twice is skipped; {@link Template} instances are immutable
 * compiled ASTs that are safe to share across threads.
 *
 * <p><b>Per-request safety:</b> this class holds no per-request state. The
 * rendering context ({@code Map<String,Object>}) is always passed in as a
 * method argument on each {@link #render} call and discarded after use.
 * Suitable as a singleton Spring bean.
 *
 * <p>Note: {@link Jinja4jChatTemplateRenderer} keeps its own independent cache
 * because it implements the {@code ChatTemplateRenderer} contract from
 * {@code gravitee-inference-api} and serves a different rendering path
 * (the model's built-in chat template, not pipeline raw_templates).
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class JinjaRenderer {

  private final Environment env;
  private final ConcurrentHashMap<String, Template> cache = new ConcurrentHashMap<>();

  /**
   * Creates a renderer with a default Jinja4j environment (auto-escaping off).
   */
  public JinjaRenderer() {
    this(Jinja4jChatTemplateRenderer.defaultEnvironment());
  }

  /**
   * Creates a renderer with a caller-supplied environment. The caller owns
   * the environment — the renderer does not mutate it.
   */
  public JinjaRenderer(Environment env) {
    this.env = env;
  }

  /**
   * Compiles (or reuses) the template and renders it against the given context.
   *
   * @param templateString the Jinja2 template source
   * @param tag            a debug label used in Jinja4j error messages
   *                       (e.g. {@code "<step>"}, {@code "<guard>"})
   * @param ctx            the rendering context (consumed read-only)
   * @return the rendered string
   */
  public String render(String templateString, String tag, Map<String, Object> ctx) {
    Template template = cache.computeIfAbsent(templateString, src -> env.fromString(src, tag));
    return template.render(ctx);
  }
}
