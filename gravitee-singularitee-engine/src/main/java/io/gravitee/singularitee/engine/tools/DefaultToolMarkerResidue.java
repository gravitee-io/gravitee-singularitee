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

import io.gravitee.singularitee.protocol.TagConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dialect-agnostic baseline: the residue markers are the step's configured
 * tool tags, verbatim — an unparsed tag surviving into the final text (e.g. a
 * leaked chatml {@code <tool_call>}) is machinery, not an answer. Dialect
 * subclasses refine each tag into further markers via {@link #refine(String)}.
 */
public class DefaultToolMarkerResidue implements ToolMarkerResidue {

  @Override
  public final boolean isPresent(String output, TagConfig toolTags) {
    if (output == null || output.isEmpty() || toolTags == null) {
      return false;
    }
    for (String marker : markers(toolTags)) {
      if (output.contains(marker)) {
        return true;
      }
    }
    return false;
  }

  /** The full residue-marker set for a tag config. */
  final Set<String> markers(TagConfig toolTags) {
    List<String> configured = new ArrayList<>();
    configured.add(toolTags.getOpenTag());
    configured.add(toolTags.getCloseTag());
    configured.addAll(toolTags.getOpenTagAlternativesList());
    configured.addAll(toolTags.getCloseTagAlternativesList());

    Set<String> markers = new LinkedHashSet<>();
    for (String tag : configured) {
      if (tag == null || tag.isBlank()) {
        continue;
      }
      markers.add(tag);
      markers.addAll(refine(tag));
    }
    return markers;
  }

  /** Extra markers a dialect derives from one configured tag. Baseline: none. */
  protected List<String> refine(String tag) {
    return List.of();
  }
}
