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
package io.gravitee.singularitee.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.TextGenRequest;
import io.gravitee.singularitee.engine.api.metrics.InferenceMetrics;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.protocol.InferRequest;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.vertx.core.Vertx;
import io.vertx.core.streams.WriteStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GraviteeInferenceServiceImplSamplingTest {

  private static final String MODEL_ID = "llm";

  private static Vertx vertx;

  private TextGenEngine engine;
  private GraviteeInferenceServiceImpl service;

  @BeforeAll
  static void startVertx() {
    vertx = Vertx.vertx();
  }

  @AfterAll
  static void stopVertx() {
    vertx.close();
  }

  @BeforeEach
  void setUp() {
    var registry = new ModelRegistry();
    engine = mock(TextGenEngine.class);
    when(engine.rxStream(anyInt())).thenReturn(Flowable.never());
    when(engine.rxAddSequence(anyInt(), any())).thenReturn(Completable.never());
    registry.register(MODEL_ID, "test-llm", engine, token -> {});
    service = new GraviteeInferenceServiceImpl(
      vertx,
      registry,
      null,
      mock(Tracer.class),
      new InferenceMetrics(null),
      false
    );
  }

  @Test
  void a_zero_temperature_is_greedy() {
    assertThat(submitted(SamplingParams.newBuilder().setTemperature(0f)).temperature()).isZero();
  }

  @Test
  void a_positive_temperature_is_forwarded() {
    assertThat(submitted(SamplingParams.newBuilder().setTemperature(0.4f)).temperature()).isEqualTo(
      0.4f
    );
  }

  @Test
  void an_unset_or_negative_temperature_leaves_the_engine_default() {
    assertThat(submitted(SamplingParams.newBuilder().setMaxTokens(16)).temperature()).isNull();
    assertThat(submitted(SamplingParams.newBuilder().setTemperature(-1f)).temperature()).isNull();
    assertThat(submitted(null).temperature()).isNull();
  }

  private TextGenRequest submitted(SamplingParams.Builder sampling) {
    var request = InferRequest.newBuilder().setModelId(MODEL_ID).setPrompt("hello");
    if (sampling != null) request.setSamplingParams(sampling);
    @SuppressWarnings("unchecked")
    WriteStream<InferResponse> response = mock(WriteStream.class);

    service.infer(request.build(), response);

    var captor = ArgumentCaptor.forClass(TextGenRequest.class);
    verify(engine, atLeastOnce()).rxAddSequence(anyInt(), captor.capture());
    return captor.getValue();
  }
}
