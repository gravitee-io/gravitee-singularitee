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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Harmony (gpt-oss) residue: beyond the verbatim tags, every {@code <|...|>}
 * special token can appear in invented combinations (e.g.
 * {@code <|channel|>functions.name} — observed live), and the {@code to=}
 * routing prefix can float free of any full tag.
 */
public final class HarmonyToolMarkerResidue extends DefaultToolMarkerResidue {

  private static final Pattern SPECIAL_TOKEN = Pattern.compile("<\\|[^|<>]+\\|>");

  @Override
  protected List<String> refine(String tag) {
    List<String> extra = new ArrayList<>();
    Matcher m = SPECIAL_TOKEN.matcher(tag);
    while (m.find()) {
      extra.add(m.group());
    }
    int to = tag.indexOf("to=");
    if (to >= 0) {
      extra.add(tag.substring(to));
    }
    return extra;
  }
}
