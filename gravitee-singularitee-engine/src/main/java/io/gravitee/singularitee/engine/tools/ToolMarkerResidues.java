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
package io.gravitee.singularitee.engine.tools;

import java.util.Map;

/**
 * Registry of {@link ToolMarkerResidue} implementations, keyed by extraction
 * template name. Adding a dialect = one implementation class + one entry here.
 */
public final class ToolMarkerResidues {

  private static final ToolMarkerResidue DEFAULT = new DefaultToolMarkerResidue();

  private static final Map<String, ToolMarkerResidue> BY_TEMPLATE = Map.of(
    "harmony",
    new HarmonyToolMarkerResidue()
  );

  private ToolMarkerResidues() {}

  /**
   * Resolves the dialect's detector from its extraction-template name; unknown
   * or custom templates get the verbatim-tag {@link DefaultToolMarkerResidue}.
   */
  public static ToolMarkerResidue forTemplate(String extractionTemplate) {
    return BY_TEMPLATE.getOrDefault(
      extractionTemplate == null ? "" : extractionTemplate.trim(),
      DEFAULT
    );
  }
}
