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
package io.gravitee.singularitee.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.engine.pipeline.executor.StepDispatcher;
import io.gravitee.singularitee.engine.pipeline.trace.TraceKeyAuthority;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.gravitee.singularitee.protocol.StepRole;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.streams.WriteStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PipelineExecutorTraceTest {

  private static final String KEY = "a-trace-key";

  private static final class Collecting implements WriteStream<InferResponse> {

    final List<InferResponse> events = new ArrayList<>();

    @Override
    public WriteStream<InferResponse> exceptionHandler(Handler<Throwable> handler) {
      return this;
    }

    @Override
    public Future<Void> write(InferResponse data) {
      events.add(data);
      return Future.succeededFuture();
    }

    @Override
    public Future<Void> end() {
      return Future.succeededFuture();
    }

    @Override
    public WriteStream<InferResponse> setWriteQueueMaxSize(int maxSize) {
      return this;
    }

    @Override
    public boolean writeQueueFull() {
      return false;
    }

    @Override
    public WriteStream<InferResponse> drainHandler(Handler<Void> handler) {
      return this;
    }

    long count(ResponseEventType type) {
      return events
        .stream()
        .filter(e -> e.getEventType() == type)
        .count();
    }
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(
      MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    );
  }

  private static Collecting run(TraceKeyAuthority authority, String presentedKey) {
    var registry = new PipelineRegistry(new ModelRegistry());
    registry.register(
      new PipelineModel(
        "p",
        "p",
        "only",
        List.of(new StepModel("only", "fake", StepRole.STEP_ROLE_INTERNAL, null)),
        Map.of(),
        "",
        false,
        List.of()
      )
    );
    StepExecutor<Object> fake = (id, config, ctx) -> {
      ctx.pipelineContext().set("only.output", "done");
      return Maybe.empty();
    };
    var executor = new PipelineExecutor(
      registry,
      new StepDispatcher(Map.of("fake", fake)),
      null,
      null,
      null,
      null,
      null,
      authority
    );
    var request = InferPipelineRequest.newBuilder().setPipelineId("p").setPrompt("hello");
    if (presentedKey != null) request.setTraceKey(presentedKey);
    var stream = new Collecting();
    executor.executePipeline(request.build(), stream, null).blockingAwait();
    return stream;
  }

  @Test
  void a_matching_key_enables_the_trace() throws Exception {
    var authority = new TraceKeyAuthority();
    authority.expect(sha256(KEY));

    var stream = run(authority, KEY);

    assertThat(stream.count(ResponseEventType.RESPONSE_EVENT_TYPE_STEP_TRACE)).isEqualTo(1);
    assertThat(stream.count(ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED)).isEqualTo(1);
  }

  @Test
  void a_wrong_or_missing_key_runs_without_trace() throws Exception {
    var authority = new TraceKeyAuthority();
    authority.expect(sha256(KEY));

    for (var presented : new String[] { "wrong", null, "" }) {
      var stream = run(authority, presented);

      assertThat(stream.count(ResponseEventType.RESPONSE_EVENT_TYPE_STEP_TRACE))
        .as(String.valueOf(presented))
        .isZero();
      assertThat(stream.count(ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED))
        .as(String.valueOf(presented))
        .isEqualTo(1);
    }
  }

  @Test
  void nothing_is_traced_before_the_control_plane_announced_a_key() {
    var stream = run(new TraceKeyAuthority(), KEY);

    assertThat(stream.count(ResponseEventType.RESPONSE_EVENT_TYPE_STEP_TRACE)).isZero();
  }

  @Test
  void a_non_hex_hash_from_the_control_plane_withdraws_tracing() throws Exception {
    var authority = new TraceKeyAuthority();
    authority.expect(sha256(KEY));
    assertThat(run(authority, KEY).count(ResponseEventType.RESPONSE_EVENT_TYPE_STEP_TRACE)).isOne();

    for (var garbage : new String[] { "not-hex-at-all", "abc", "zz".repeat(32) }) {
      authority.expect(garbage);

      var stream = run(authority, KEY);

      assertThat(stream.count(ResponseEventType.RESPONSE_EVENT_TYPE_STEP_TRACE))
        .as(garbage)
        .isZero();
      assertThat(stream.count(ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED))
        .as(garbage)
        .isEqualTo(1);
    }
  }

  @Test
  void a_non_hex_first_announcement_traces_nothing() {
    var authority = new TraceKeyAuthority();
    authority.expect("definitely not a sha-256 digest");

    var stream = run(authority, KEY);

    assertThat(stream.count(ResponseEventType.RESPONSE_EVENT_TYPE_STEP_TRACE)).isZero();
  }
}
