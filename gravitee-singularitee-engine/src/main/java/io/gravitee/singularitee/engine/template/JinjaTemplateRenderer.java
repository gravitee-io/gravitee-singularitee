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
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateRenderer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link TemplateRenderer} backed by <b>Jinja4j</b>, a pure-Java Jinja engine:
 * no Python interpreter, no native code, no FFI. The default renderer wired into
 * pipeline execution.
 *
 * <p>Owns the two process-lifetime Jinja4j resources, the {@link Environment}
 * and the compiled-{@link Template} cache. Template strings are keyed by their
 * source so compiling the same template twice is skipped; {@link Template}
 * instances are immutable compiled ASTs safe to share across threads. The
 * renderer holds no per-request state, so a single instance is safe as a
 * shared singleton.
 */
public final class JinjaTemplateRenderer implements TemplateRenderer {

  private final Environment env;
  private final ConcurrentHashMap<String, Template> cache = new ConcurrentHashMap<>();

  /** A renderer over a fresh environment with auto-escaping off (templates render literal text). */
  public JinjaTemplateRenderer() {
    this(defaultEnvironment());
  }

  /**
   * A renderer over a caller-supplied environment. The caller owns the
   * environment; the renderer does not mutate it.
   */
  public JinjaTemplateRenderer(Environment env) {
    this.env = env;
  }

  /**
   * A baseline Jinja4j environment with auto-escaping off. LLM prompts and
   * chat templates emit raw text (special tokens, markup) that must never be
   * HTML-escaped; callers may layer their own customisations on top.
   */
  public static Environment defaultEnvironment() {
    Environment e = new Environment();
    e.setAutoEscaping(false);
    return e;
  }

  @Override
  public String render(String templateString, String tag, Map<String, Object> ctx) {
    Template template = cache.computeIfAbsent(templateString, src -> env.fromString(src, tag));
    return template.render(ctx);
  }
}
