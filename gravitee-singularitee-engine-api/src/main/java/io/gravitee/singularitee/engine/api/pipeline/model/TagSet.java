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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.protocol.TagConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The reasoning and tool-call marker set of a text-generation step's {@code tags:} block,
 * inline or a reference (a bare string) into the workspace {@code tags:} section. Shared by
 * every step that runs a text-generation model, so a dialect such as Harmony is declared once
 * and the engine, not the step, tells reasoning from the answer.
 *
 * <p>Each marker key accepts a single string or a list: the first entry is the primary marker,
 * the rest ride along as alternatives.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TagSet(
  @JsonProperty("id") String id,
  @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
  @JsonProperty("reasoning_open")
  List<String> reasoningOpen,
  @JsonProperty("reasoning_repeatable") Boolean reasoningRepeatable,
  @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
  @JsonProperty("reasoning_close")
  List<String> reasoningClose,
  @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
  @JsonProperty("tool_open")
  List<String> toolOpen,
  @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
  @JsonProperty("tool_close")
  List<String> toolClose
) {
  /** The workspace section named tag sets live in. */
  public static final String SECTION = "tags";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static TagSet ref(String id) {
    return new TagSet(id, null, null, null, null, null);
  }

  /** Whether this value is a bare reference to a workspace {@code tags:} entry. */
  public boolean isReference() {
    return (
      id != null &&
      reasoningOpen == null &&
      reasoningClose == null &&
      toolOpen == null &&
      toolClose == null &&
      reasoningRepeatable == null
    );
  }

  /**
   * Resolves a reference against the workspace section; an inline set (or {@code null}) is
   * returned as is. An unknown id fails the load rather than silently running untagged.
   */
  public static TagSet resolve(String stepId, TagSet tags, StepCodecContext ctx) {
    if (tags == null || !tags.isReference()) {
      return tags;
    }
    Map<String, Object> named = ctx.section(SECTION).get(tags.id());
    if (named == null) {
      throw new IllegalArgumentException(
        "Step '" +
          stepId +
          "': unknown tags id '" +
          tags.id() +
          "': declare it under workspace tags:"
      );
    }
    try {
      return MAPPER.convertValue(named, TagSet.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Step '" + stepId + "': invalid tags entry '" + tags.id() + "': " + e.getMessage(),
        e
      );
    }
  }

  /** The reasoning markers as a wire {@link TagConfig}, or {@code null} when none is set. */
  public TagConfig reasoningTags() {
    return toTagConfig(reasoningOpen, reasoningClose, reasoningRepeatable);
  }

  /** The tool-call markers as a wire {@link TagConfig}, or {@code null} when none is set. */
  public TagConfig toolCallTags() {
    return toTagConfig(toolOpen, toolClose, null);
  }

  /**
   * Maps a wire tag pair to the engine's own tag type, alternatives and the repeatable flag
   * included; a blank open tag means the pair is unset and yields {@code null}. The engine type
   * shares the wire message's simple name, hence the qualified return type.
   */
  public static io.gravitee.singularitee.inference.api.textgen.TagConfig toEngine(TagConfig t) {
    if (t == null || t.getOpenTag().isBlank()) {
      return null;
    }
    return new io.gravitee.singularitee.inference.api.textgen.TagConfig(
      t.getOpenTag(),
      t.getCloseTag(),
      t.getOpenTagAlternativesList(),
      t.getCloseTagAlternativesList(),
      t.hasRepeatable() ? t.getRepeatable() : null
    );
  }

  private static TagConfig toTagConfig(
    List<String> opens,
    List<String> closes,
    Boolean repeatable
  ) {
    var openList = nonBlank(opens);
    if (openList.isEmpty()) {
      return null;
    }
    var closeList = nonBlank(closes);
    var tag = TagConfig.newBuilder()
      .setOpenTag(openList.getFirst())
      .setCloseTag(closeList.isEmpty() ? "" : closeList.getFirst());
    openList.stream().skip(1).forEach(tag::addOpenTagAlternatives);
    closeList.stream().skip(1).forEach(tag::addCloseTagAlternatives);
    // Left unset when the workspace is silent, so the engine's own rule applies.
    if (repeatable != null) {
      tag.setRepeatable(repeatable);
    }
    return tag.build();
  }

  private static List<String> nonBlank(List<String> values) {
    return values == null
      ? List.of()
      : values
        .stream()
        .filter(Objects::nonNull)
        .filter(v -> !v.isBlank())
        .toList();
  }
}
