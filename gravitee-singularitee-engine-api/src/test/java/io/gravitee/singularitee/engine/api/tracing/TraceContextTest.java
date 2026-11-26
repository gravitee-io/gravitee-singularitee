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
package io.gravitee.singularitee.engine.api.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link TraceContext}: W3C headers of a span, bound to the calls made under them. */
class TraceContextTest {

  @Test
  void nothing_is_bound_outside_a_call() {
    assertThat(TraceContext.current()).isEmpty();
  }

  @Test
  void a_call_sees_its_headers_and_the_previous_ones_come_back_after_it() {
    var outer = Map.of("traceparent", "outer");
    var inner = Map.of("traceparent", "inner");

    var seen = TraceContext.callWith(
      outer,
      () ->
        TraceContext.current().get("traceparent") +
        "," +
        TraceContext.callWith(inner, () -> TraceContext.current().get("traceparent")) +
        "," +
        TraceContext.current().get("traceparent")
    );

    assertThat(seen).isEqualTo("outer,inner,outer");
    assertThat(TraceContext.current()).isEmpty();
  }

  @Test
  void the_headers_of_a_span_name_its_trace_and_its_id() throws Exception {
    try (var tracing = new RecordingTracer()) {
      var span = tracing.start("parent");

      var headers = TraceContext.headersOf(tracing.tracer(), tracing.context(), span);

      assertThat(headers).containsEntry(
        "traceparent",
        "00-" + span.traceId() + "-" + span.spanId() + "-01"
      );
    }
  }

  @Test
  void without_a_tracer_or_a_span_there_are_no_headers() throws Exception {
    try (var tracing = new RecordingTracer()) {
      assertThat(TraceContext.headersOf(null, tracing.context(), tracing.start("s"))).isEmpty();
      assertThat(TraceContext.headersOf(tracing.tracer(), tracing.context(), null)).isEmpty();
    }
  }
}
