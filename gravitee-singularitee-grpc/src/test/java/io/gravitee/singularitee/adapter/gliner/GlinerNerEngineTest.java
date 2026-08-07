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

import io.gravitee.lab.gliner4j.GLiNER4jNER;
import io.gravitee.lab.gliner4j.schema.EntityDefinition;
import io.gravitee.lab.gliner4j.schema.EntitySpan;
import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Per-request entity handling and micro-batched chunking for the GLiNER NER engine.
 */
class GlinerNerEngineTest {

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

  @Test
  void per_request_entities_reach_the_definition_aware_overload() {
    var delegate = mock(GLiNER4jNER.class);
    when(delegate.extract(anyString(), anyList(), anyFloat())).thenReturn(
      Map.of("person", List.of(new EntitySpan("person", "John", 0.9f, 0, 4)))
    );

    var engine = new GlinerNerEngine(delegate, THRESHOLD, 512, List.of(), vertx);
    var labels = List.of(new ClassifierEngine.ClassifyLabel("person", "A person's name"));

    var response = engine
      .rxClassify(new ClassifyRequest("John lives in Paris"), labels)
      .blockingGet();

    assertThat(response.results()).isNotEmpty();
    assertThat(response.results().get(0).label()).isEqualTo("person");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<EntityDefinition>> captor = ArgumentCaptor.forClass(List.class);
    verify(delegate).extract(anyString(), captor.capture(), eq(THRESHOLD));
    assertThat(captor.getValue()).extracting(EntityDefinition::name).containsExactly("person");
    // Custom entities can't be batched: the single-call overload is used, never extractBatch.
    verify(delegate, never()).extract(anyString(), anyFloat());
    verify(delegate, never()).extractBatch(anyList(), anyFloat());
  }

  @Test
  void empty_per_request_entities_fall_back_to_the_configured_entities_via_batch() {
    var delegate = mock(GLiNER4jNER.class);
    // Default-schema path now goes through the batched overload.
    when(delegate.extractBatch(anyList(), anyFloat())).thenReturn(List.of(Map.of()));

    var engine = new GlinerNerEngine(delegate, THRESHOLD, 512, List.of("email"), vertx);

    var response = engine
      .rxClassify(new ClassifyRequest("no entities here"), List.of())
      .blockingGet();

    assertThat(response.results()).isEmpty();
    verify(delegate).extractBatch(anyList(), eq(THRESHOLD));
    verify(delegate, never()).extract(anyString(), anyFloat());
    verify(delegate, never()).extract(anyString(), anyList(), anyFloat());
  }

  @Test
  void blank_input_short_circuits_without_touching_the_model() {
    var delegate = mock(GLiNER4jNER.class);

    var engine = new GlinerNerEngine(delegate, THRESHOLD, 512, List.of("email"), vertx);

    var response = engine.rxClassify(new ClassifyRequest("   ")).blockingGet();

    assertThat(response.results()).isEmpty();
    verify(delegate, never()).extractBatch(anyList(), anyFloat());
    verify(delegate, never()).extract(anyString(), anyFloat());
  }

  /**
   * A single request that splits into more chunks than {@link GlinerBatching#maxBatchSize()} must
   * still reassemble every span at its correct absolute offset — even though its chunks are spread
   * across multiple {@code extractBatch} GPU runs (the batcher hard-caps each batch, so more chunks
   * than one batch's worth is guaranteed to span ≥2 calls).
   */
  @Test
  void large_input_reassembles_spans_correctly_across_multiple_batches() {
    var delegate = mock(GLiNER4jNER.class);
    // Echo one span per chunk covering the whole chunk, so offset re-basing is directly checkable.
    when(delegate.extractBatch(anyList(), anyFloat())).thenAnswer(inv -> {
      List<String> texts = inv.getArgument(0);
      List<Map<String, List<EntitySpan>>> out = new ArrayList<>(texts.size());
      for (String t : texts) {
        out.add(Map.of("chunk", List.of(new EntitySpan("chunk", t, 1f, 0, t.length()))));
      }
      return out;
    });

    // Small token cap forces many small chunks; assert we exceed one batch's worth.
    int tokenCap = 20;
    String text = buildWordyText(400);
    int budget = GlinerChunking.textBudget(tokenCap, List.of());
    int chunkCount = GlinerChunking.splitter(budget).split(text).size();
    assertThat(chunkCount)
      .as("test must produce more chunks than a single batch to exercise cross-batch reassembly")
      .isGreaterThan(GlinerBatching.CONFIG.maxBatchSize());

    var engine = new GlinerNerEngine(delegate, THRESHOLD, tokenCap, List.of(), vertx);

    var response = engine.rxClassify(new ClassifyRequest(text)).blockingGet();

    // Exactly one span per chunk — nothing dropped or duplicated across batches.
    assertThat(response.results()).hasSize(chunkCount);
    // Every span's absolute offsets slice the original text back to its own token: the re-basing is
    // correct regardless of which batch produced the chunk.
    for (var r : response.results()) {
      assertThat(text.substring(r.start(), r.end())).isEqualTo(r.token());
    }

    // The chunks really were split across ≥2 batched GPU calls, and every chunk was processed once.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(delegate, atLeast(2)).extractBatch(captor.capture(), eq(THRESHOLD));
    int totalBatched = captor.getAllValues().stream().mapToInt(List::size).sum();
    assertThat(totalBatched).isEqualTo(chunkCount);
    assertThat(captor.getAllValues()).allSatisfy(batch ->
      assertThat(batch.size()).isLessThanOrEqualTo(GlinerBatching.CONFIG.maxBatchSize())
    );
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
