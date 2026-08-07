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
package io.gravitee.singularitee.inference.api.textgen;

import java.util.List;

/**
 * Channel markers. {@code openAlternatives} carries additional opening markers for dialects that
 * open the same channel more than one way — Harmony emits tool calls on both the commentary and
 * analysis channels, and a variant that is not configured leaks into the previous channel as text.
 */
public record TagConfig(
  String openToken,
  String closeToken,
  List<String> openAlternatives,
  List<String> closeAlternatives,
  Boolean repeatable
) {
  public TagConfig {
    openAlternatives = openAlternatives == null ? List.of() : List.copyOf(openAlternatives);
    closeAlternatives = closeAlternatives == null ? List.of() : List.copyOf(closeAlternatives);
  }

  /** Without an explicit re-entry rule — the engine's own default applies. */
  public TagConfig(
    String openToken,
    String closeToken,
    List<String> openAlternatives,
    List<String> closeAlternatives
  ) {
    this(openToken, closeToken, openAlternatives, closeAlternatives, null);
  }

  public TagConfig(String openToken, String closeToken) {
    this(openToken, closeToken, List.of(), List.of());
  }

  public TagConfig(String openToken, String closeToken, List<String> openAlternatives) {
    this(openToken, closeToken, openAlternatives, List.of());
  }

  /** Every opening marker, primary first. */
  public List<String> allOpenTokens() {
    if (openToken == null || openToken.isBlank()) {
      return List.of();
    }
    if (openAlternatives.isEmpty()) {
      return List.of(openToken);
    }
    var all = new java.util.ArrayList<String>(openAlternatives.size() + 1);
    all.add(openToken);
    all.addAll(openAlternatives);
    return List.copyOf(all);
  }

  /**
   * Every closing marker, primary first.
   *
   * <p>A channel may be left more than one way: Harmony reaches the final channel
   * after {@code <|end|>} when the model answers directly and after {@code <|call|>}
   * when a tool call intervened, and the marker that is not configured leaks its
   * header into the answer.
   */
  public List<String> allCloseTokens() {
    if (closeToken == null || closeToken.isBlank()) {
      return List.of();
    }
    if (closeAlternatives.isEmpty()) {
      return List.of(closeToken);
    }
    var all = new java.util.ArrayList<String>(closeAlternatives.size() + 1);
    all.add(closeToken);
    all.addAll(closeAlternatives);
    return List.copyOf(all);
  }

  public boolean isConfigured() {
    return openToken != null && !openToken.isBlank() && closeToken != null && !closeToken.isBlank();
  }
}
