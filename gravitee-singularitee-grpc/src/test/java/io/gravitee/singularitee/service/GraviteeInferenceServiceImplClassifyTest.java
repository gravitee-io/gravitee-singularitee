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
import static org.mockito.Mockito.mock;

import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.gravitee.singularitee.engine.ClassifyResponse;
import io.gravitee.singularitee.metrics.InferenceMetrics;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for the unary {@code Classify} RPC: per-request labels must be
 * forwarded to the engine, matching {@code ClassifyBatch} semantics.
 */
class GraviteeInferenceServiceImplClassifyTest {

  private static final String MODEL_ID = "sentence-classifier";

  private static Vertx vertx;

  private RecordingClassifierEngine engine;
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
    engine = new RecordingClassifierEngine();
    registry.register(MODEL_ID, "test-classifier", engine, token -> {});
    service = new GraviteeInferenceServiceImpl(
      vertx,
      registry,
      null,
      mock(Tracer.class),
      new InferenceMetrics(null)
    );
  }

  @Test
  void classify_with_per_request_labels_forwards_them_to_the_engine() throws Exception {
    var request = io.gravitee.singularitee.protocol.ClassifyRequest.newBuilder()
      .setModelId(MODEL_ID)
      .setText("I want to see all available pets")
      .addLabels(protoLabel("browse_catalog", "Browse or search the pet catalog"))
      .addLabels(protoLabel("place_order", "Place or manage an order"))
      .addLabels(protoLabel("account_help", ""))
      .build();

    var response = await(service.classify(request));

    assertThat(engine.receivedLabels)
      .extracting(ClassifierEngine.ClassifyLabel::name)
      .containsExactly("browse_catalog", "place_order", "account_help");
    assertThat(engine.receivedLabels.get(0).description()).isEqualTo(
      "Browse or search the pet catalog"
    );
    assertThat(response.getTopLabel()).isEqualTo("browse_catalog");
  }

  @Test
  void classify_without_labels_uses_the_model_default_labels() throws Exception {
    var request = io.gravitee.singularitee.protocol.ClassifyRequest.newBuilder()
      .setModelId(MODEL_ID)
      .setText("I want to see all available pets")
      .build();

    var response = await(service.classify(request));

    assertThat(response.getTopLabel()).isEqualTo(RecordingClassifierEngine.DEFAULT_PATH_LABEL);
  }

  private static io.gravitee.singularitee.protocol.ClassifyLabel protoLabel(
    String name,
    String description
  ) {
    return io.gravitee.singularitee.protocol.ClassifyLabel.newBuilder()
      .setName(name)
      .setDescription(description)
      .build();
  }

  private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  /**
   * Fake engine that records the labels it receives. The label-aware path answers with
   * the first label's name; the label-less path answers with {@link #DEFAULT_PATH_LABEL}.
   */
  private static final class RecordingClassifierEngine implements ClassifierEngine {

    static final String DEFAULT_PATH_LABEL = "configured-path";

    volatile List<ClassifierEngine.ClassifyLabel> receivedLabels = List.of();

    @Override
    public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
      return Single.just(
        new ClassifyResponse(DEFAULT_PATH_LABEL, 1.0f, Map.of(DEFAULT_PATH_LABEL, 1.0f), List.of())
      );
    }

    @Override
    public Single<ClassifyResponse> rxClassify(
      ClassifyRequest request,
      List<ClassifierEngine.ClassifyLabel> labels
    ) {
      receivedLabels = labels;
      if (labels == null || labels.isEmpty()) {
        return rxClassify(request);
      }
      String top = labels.get(0).name();
      return Single.just(new ClassifyResponse(top, 0.9f, Map.of(top, 0.9f), List.of()));
    }

    @Override
    public void close() {}
  }
}
