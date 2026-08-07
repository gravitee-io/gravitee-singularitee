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
package io.gravitee.singularitee.adapter.gliner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.lab.gliner4j.GLiNER4jClassifier;
import io.gravitee.lab.gliner4j.schema.ClassificationLabel;
import io.gravitee.lab.gliner4j.schema.ClassificationResult;
import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Per-request label handling for the GLiNER classifier engine: labels supplied at
 * request time must reach the delegate's label-aware overload, even when the model
 * was loaded without configured labels (zero-shot usage).
 */
class GlinerClassifierEngineTest {

  private static final float THRESHOLD = 0.05f;

  private static Vertx vertx;

  @BeforeAll
  static void startVertx() {
    vertx = Vertx.vertx();
  }

  @AfterAll
  static void stopVertx() {
    vertx.close();
  }

  private static GlinerClassifierEngine engine(
    GLiNER4jClassifier delegate,
    List<String> configLabelNames
  ) {
    return new GlinerClassifierEngine(delegate, THRESHOLD, 512, configLabelNames, vertx);
  }

  @Test
  void per_request_labels_reach_the_label_aware_overload_when_no_labels_configured() {
    var delegate = mock(GLiNER4jClassifier.class);
    when(delegate.classify(anyString(), anyList(), anyFloat())).thenReturn(
      List.of(new ClassificationResult("browse_catalog", 0.91f))
    );

    var labels = List.of(
      new ClassifierEngine.ClassifyLabel("browse_catalog", "Browse or search the pet catalog"),
      new ClassifierEngine.ClassifyLabel("place_order", "Place or manage an order"),
      new ClassifierEngine.ClassifyLabel("account_help", "Help with the user account")
    );

    var response = engine(delegate, List.of())
      .rxClassify(new ClassifyRequest("I want to see all available pets"), labels)
      .blockingGet();

    assertThat(response.topLabel()).isEqualTo("browse_catalog");
    assertThat(response.topScore()).isEqualTo(0.91f);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ClassificationLabel>> captor = ArgumentCaptor.forClass(List.class);
    verify(delegate).classify(anyString(), captor.capture(), eq(THRESHOLD));
    assertThat(captor.getValue())
      .extracting(ClassificationLabel::name)
      .containsExactly("browse_catalog", "place_order", "account_help");
    // Custom labels can't be batched: the single-call overload is used, never classifyBatch.
    verify(delegate, never()).classify(anyString(), anyFloat());
    verify(delegate, never()).classifyBatch(anyList(), anyFloat());
  }

  @Test
  void empty_per_request_labels_fall_back_to_the_configured_labels_via_batch() {
    var delegate = mock(GLiNER4jClassifier.class);
    // Default-schema path now goes through the batched overload.
    when(delegate.classifyBatch(anyList(), anyFloat())).thenReturn(
      List.of(List.of(new ClassificationResult("toxicity", 0.8f)))
    );

    var response = engine(delegate, List.of("toxicity", "safe"))
      .rxClassify(new ClassifyRequest("some text"), List.of())
      .blockingGet();

    assertThat(response.topLabel()).isEqualTo("toxicity");
    verify(delegate).classifyBatch(anyList(), eq(THRESHOLD));
    verify(delegate, never()).classify(anyString(), anyFloat());
    verify(delegate, never()).classify(anyString(), anyList(), anyFloat());
  }

  @Test
  void blank_input_short_circuits_without_touching_the_model() {
    var delegate = mock(GLiNER4jClassifier.class);

    var response = engine(delegate, List.of("toxicity"))
      .rxClassify(new ClassifyRequest(""))
      .blockingGet();

    assertThat(response.results()).isEmpty();
    verify(delegate, never()).classifyBatch(anyList(), anyFloat());
    verify(delegate, never()).classify(anyString(), anyFloat());
  }

  /**
   * A single request that splits into more chunks than {@link GlinerBatching#maxBatchSize()} must
   * keep the max-per-label rollup consistent even though its chunks are classified across multiple
   * {@code classifyBatch} GPU runs.
   */
  @Test
  void large_input_aggregates_scores_across_multiple_batches() {
    var delegate = mock(GLiNER4jClassifier.class);
    // Score the label by chunk length so the max lands on a deterministic (the longest) chunk.
    when(delegate.classifyBatch(anyList(), anyFloat())).thenAnswer(inv -> {
      List<String> texts = inv.getArgument(0);
      List<List<ClassificationResult>> out = new ArrayList<>(texts.size());
      for (String t : texts) {
        out.add(List.of(new ClassificationResult("toxicity", scoreFor(t))));
      }
      return out;
    });

    int tokenCap = 20;
    String text = buildWordyText(400);
    int budget = GlinerChunking.textBudget(tokenCap, List.of("toxicity"));
    var chunks = GlinerChunking.splitter(budget).split(text);
    assertThat(chunks.size())
      .as("test must produce more chunks than a single batch")
      .isGreaterThan(GlinerBatching.CONFIG.maxBatchSize());
    float expectedMax = chunks
      .stream()
      .map(c -> scoreFor(c.text()))
      .max(Float::compare)
      .orElseThrow();

    var engine = new GlinerClassifierEngine(
      delegate,
      THRESHOLD,
      tokenCap,
      List.of("toxicity"),
      vertx
    );

    var response = engine.rxClassify(new ClassifyRequest(text)).blockingGet();

    // Max-per-label rollup holds across batch boundaries; one per-chunk row per chunk.
    assertThat(response.topLabel()).isEqualTo("toxicity");
    assertThat(response.topScore()).isEqualTo(expectedMax);
    assertThat(response.allScores().get("toxicity")).isEqualTo(expectedMax);
    assertThat(response.results()).hasSize(chunks.size());

    verify(delegate, atLeast(2)).classifyBatch(anyList(), eq(THRESHOLD));
  }

  private static float scoreFor(String text) {
    // Deterministic, monotonic in length, bounded to (0, 1).
    return Math.min(0.99f, 0.10f + text.length() * 0.001f);
  }

  private static String buildWordyText(int words) {
    String[] vocab = { "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel" };
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < words; i++) {
      if (i > 0) {
        sb.append(' ');
      }
      sb.append(vocab[i % vocab.length]);
    }
    return sb.toString();
  }
}
