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

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JinjaContextHelper}'s Struct conversion and step
 * context merging.
 *
 * <p>The Boolean round-trip cases pin the property the whole Qwen3
 * thinking-bypass depends on: {@code enable_thinking: false} must arrive at
 * the Jinja renderer as a Java {@link Boolean}, because the template's
 * {@code enable_thinking is false} guard is a strict boolean-identity test —
 * a String {@code "false"} (quoted YAML) or a Double {@code 0.0} silently
 * disables the bypass.
 */
class JinjaContextHelperTest {

  // ── mapToStruct / structToMap round-trip ──────────────────────────────────

  @Nested
  class StructRoundTrip {

    @Test
    void boolean_false_survives_round_trip_as_boolean() {
      Map<String, Object> in = Map.of("enable_thinking", Boolean.FALSE);

      Map<String, Object> out = JinjaContextHelper.structToMap(JinjaContextHelper.mapToStruct(in));

      assertThat(out.get("enable_thinking")).isInstanceOf(Boolean.class).isEqualTo(Boolean.FALSE);
    }

    @Test
    void boolean_true_survives_round_trip_as_boolean() {
      Map<String, Object> out = JinjaContextHelper.structToMap(
        JinjaContextHelper.mapToStruct(Map.of("enable_thinking", Boolean.TRUE))
      );

      assertThat(out.get("enable_thinking")).isInstanceOf(Boolean.class).isEqualTo(Boolean.TRUE);
    }

    @Test
    void string_false_stays_a_string_not_a_boolean() {
      // A quoted YAML boolean must not be silently "fixed" by the transport —
      // the F4 warning in mergeStepContext is the guard rail, not coercion.
      Map<String, Object> out = JinjaContextHelper.structToMap(
        JinjaContextHelper.mapToStruct(Map.of("enable_thinking", "false"))
      );

      assertThat(out.get("enable_thinking")).isInstanceOf(String.class).isEqualTo("false");
    }

    @Test
    void nested_maps_and_lists_survive_round_trip() {
      Map<String, Object> in = Map.of(
        "nested",
        Map.of("flag", Boolean.FALSE, "name", "qwen3"),
        "list",
        List.of("a", Boolean.TRUE, 3.5)
      );

      Map<String, Object> out = JinjaContextHelper.structToMap(JinjaContextHelper.mapToStruct(in));

      assertThat(out.get("nested"))
        .isInstanceOf(Map.class)
        .isEqualTo(Map.of("flag", Boolean.FALSE, "name", "qwen3"));
      assertThat(out.get("list")).isEqualTo(List.of("a", Boolean.TRUE, 3.5));
    }

    @Test
    void numbers_round_trip_as_double() {
      // proto Struct has a single number kind — Integer widens to Double.
      Map<String, Object> out = JinjaContextHelper.structToMap(
        JinjaContextHelper.mapToStruct(Map.of("max_loops", 3))
      );

      assertThat(out.get("max_loops")).isEqualTo(3.0);
    }

    @Test
    void null_value_round_trips_to_null() {
      var in = new LinkedHashMap<String, Object>();
      in.put("absent", null);

      Map<String, Object> out = JinjaContextHelper.structToMap(JinjaContextHelper.mapToStruct(in));

      assertThat(out).containsKey("absent");
      assertThat(out.get("absent")).isNull();
    }
  }

  // ── mergeStepContext ──────────────────────────────────────────────────────

  @Nested
  class MergeStepContext {

    @Test
    void boolean_false_is_merged_as_boolean() {
      var ctx = new LinkedHashMap<String, Object>();
      var struct = Struct.newBuilder()
        .putFields("enable_thinking", Value.newBuilder().setBoolValue(false).build())
        .build();

      JinjaContextHelper.mergeStepContext(ctx, struct);

      assertThat(ctx.get("enable_thinking")).isInstanceOf(Boolean.class).isEqualTo(Boolean.FALSE);
    }

    @Test
    void string_false_is_merged_unchanged_despite_warning() {
      // The merge warns (no logging backend to assert here) but must NOT
      // coerce — the value stays a String so behavior is predictable.
      var ctx = new LinkedHashMap<String, Object>();
      var struct = Struct.newBuilder()
        .putFields("enable_thinking", Value.newBuilder().setStringValue("false").build())
        .build();

      JinjaContextHelper.mergeStepContext(ctx, struct);

      assertThat(ctx.get("enable_thinking")).isInstanceOf(String.class).isEqualTo("false");
    }

    @Test
    void step_context_overrides_existing_keys() {
      var ctx = new LinkedHashMap<String, Object>();
      ctx.put("enable_thinking", Boolean.TRUE);
      var struct = Struct.newBuilder()
        .putFields("enable_thinking", Value.newBuilder().setBoolValue(false).build())
        .build();

      JinjaContextHelper.mergeStepContext(ctx, struct);

      assertThat(ctx.get("enable_thinking")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void null_or_empty_struct_is_a_no_op() {
      var ctx = new LinkedHashMap<String, Object>();
      ctx.put("keep", "me");

      JinjaContextHelper.mergeStepContext(ctx, null);
      JinjaContextHelper.mergeStepContext(ctx, Struct.getDefaultInstance());

      assertThat(ctx).containsExactly(Map.entry("keep", "me"));
    }
  }
}
