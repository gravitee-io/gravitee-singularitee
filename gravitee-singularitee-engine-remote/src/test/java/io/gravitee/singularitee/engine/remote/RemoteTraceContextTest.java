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
package io.gravitee.singularitee.engine.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.client.SingulariteeClient;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.EmbedRequest;
import io.gravitee.singularitee.engine.api.RerankRequest;
import io.gravitee.singularitee.engine.api.TextGenRequest;
import io.gravitee.singularitee.engine.api.tracing.PropagatesTraceContext;
import io.gravitee.singularitee.engine.api.tracing.TraceContext;
import io.gravitee.singularitee.protocol.ClassifyResponse;
import io.gravitee.singularitee.protocol.EmbedResponse;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.TextRerankResponse;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.streams.WriteStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A remote engine sends the trace context bound to its call as gRPC metadata. */
class RemoteTraceContextTest {

  private static final Map<String, String> HEADERS = Map.of(
    "traceparent",
    "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
  );

  private final SingulariteeClient client = mock(SingulariteeClient.class);
  private final SingulariteeClient traced = mock(SingulariteeClient.class);

  RemoteTraceContextTest() {
    when(client.withMetadata(HEADERS)).thenReturn(traced);
  }

  @Test
  void every_remote_engine_propagates_trace_context() {
    assertThat(new RemoteClassifierEngine(client, "pii", null)).isInstanceOf(
      PropagatesTraceContext.class
    );
    assertThat(new RemoteTextGenEngine(client, "llm", null, null, null, List.of())).isInstanceOf(
      PropagatesTraceContext.class
    );
    assertThat(new RemoteEmbeddingEngine(client, "e")).isInstanceOf(PropagatesTraceContext.class);
    assertThat(new RemoteRerankerEngine(client, "r")).isInstanceOf(PropagatesTraceContext.class);
    assertThat(new RemoteDecisionEngine(client, "d")).isInstanceOf(PropagatesTraceContext.class);
  }

  @Test
  void a_classification_carries_the_bound_trace_context() {
    when(traced.classify(any())).thenReturn(
      Single.just(ClassifyResponse.newBuilder().setTopLabel("safe").build())
    );
    var engine = new RemoteClassifierEngine(client, "pii", null);

    var response = TraceContext.callWith(HEADERS, () ->
      engine.rxClassify(new ClassifyRequest("hi"))
    ).blockingGet();

    assertThat(response.topLabel()).isEqualTo("safe");
    verify(client, never()).classify(any());
  }

  @Test
  void a_call_outside_a_bound_context_sends_no_metadata() {
    when(client.classify(any())).thenReturn(
      Single.just(ClassifyResponse.newBuilder().setTopLabel("safe").build())
    );

    new RemoteClassifierEngine(client, "pii", null)
      .rxClassify(new ClassifyRequest("hi"))
      .blockingGet();

    verify(client, never()).withMetadata(any());
  }

  @Test
  void a_generation_carries_the_bound_trace_context() {
    when(traced.infer(any())).thenReturn(Flowable.empty());
    var engine = new RemoteTextGenEngine(client, "llm", null, null, null, List.of());
    engine.start(token -> {});
    var request = new TextGenRequest(
      "hi",
      null,
      8,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null
    );

    TraceContext.callWith(HEADERS, () -> engine.rxAddSequence(1, request)).blockingAwait();

    verify(traced).infer(any());
    verify(client, never()).infer(any());
  }

  @Test
  void an_embedding_and_a_rerank_carry_the_bound_trace_context() {
    when(traced.embed(any())).thenReturn(Single.just(EmbedResponse.getDefaultInstance()));
    when(traced.textRerank(any())).thenReturn(Single.just(TextRerankResponse.getDefaultInstance()));

    TraceContext.callWith(HEADERS, () ->
      new RemoteEmbeddingEngine(client, "e").rxEmbed(new EmbedRequest("hi"))
    ).blockingGet();
    TraceContext.callWith(HEADERS, () ->
      new RemoteRerankerEngine(client, "r").rxRerank(new RerankRequest("q", List.of("d"), 1))
    ).blockingGet();

    verify(traced).embed(any());
    verify(traced).textRerank(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void a_remote_sub_pipeline_carries_the_bound_trace_context() {
    when(traced.inferPipeline(any())).thenReturn(Flowable.empty());

    TraceContext.callWith(HEADERS, () ->
      new RemotePipelineCallback(client).executePipeline(
        InferPipelineRequest.newBuilder().setPipelineId("p").build(),
        mock(WriteStream.class),
        null
      )
    ).blockingAwait();

    verify(traced).inferPipeline(any());
    verify(client, never()).inferPipeline(any());
  }
}
