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
package io.gravitee.singularitee.engine.api.pipeline.model;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * What a step config codec may consult while parsing a step's YAML block: the workspace's
 * named sections as raw maps, and the template resolver. The loader builds one per
 * workspace; codecs read from it and never mutate it.
 *
 * @param templates       named workspace templates, id to resolved Jinja source
 * @param templatesBasePath directory that relative template files resolve against
 * @param namedSections   other workspace-level named sections a step may reference
 *                        (for example tag sets or policy sets), section name to id to raw
 *                        YAML map
 * @param templateResolver resolves a template id, file or inline source to its content
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record StepCodecContext(
  Map<String, String> templates,
  Path templatesBasePath,
  Map<String, Map<String, Map<String, Object>>> namedSections,
  Function<String, String> templateResolver
) {
  public StepCodecContext {
    templates = templates == null ? Map.of() : Map.copyOf(templates);
    namedSections = namedSections == null ? Map.of() : Map.copyOf(namedSections);
    templateResolver = templateResolver == null ? Function.identity() : templateResolver;
  }

  /** The raw entries of a named section (empty when the workspace declares none). */
  public Map<String, Map<String, Object>> section(String name) {
    return namedSections.getOrDefault(name, Map.of());
  }
}
