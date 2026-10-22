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
package io.gravitee.singularitee.adapter.classifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.inference.api.classifier.ClassifierMode;
import io.gravitee.singularitee.inference.api.classifier.ClassifierResult;
import io.gravitee.singularitee.inference.api.classifier.ClassifierResults;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter.Chunk;
import io.gravitee.singularitee.inference.onnx.bert.classifier.OnnxBertClassifierModel;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Micro-batched chunk handling for the ONNX classifier engine in both modes (mocked delegate). */
class OnnxClassifierEngineTest {

  private static Vertx vertx;

  @BeforeAll
  static void startVertx() {
    vertx = Vertx.vertx();
  }

  @AfterAll
  static void stopVertx() {
    vertx.close();
  }

  /** Delegate stub: split on '|', classify each text as label "toxic" scored text.length()/10. */
  private static OnnxBertClassifierModel mockModel() {
    var delegate = mock(OnnxBertClassifierModel.class);
    when(delegate.split(anyString())).thenAnswer(inv -> {
      String text = inv.getArgument(0);
      List<Chunk> chunks = new ArrayList<>();
      int offset = 0;
      for (String part : text.split("\\|")) {
        chunks.add(new Chunk(part, offset, offset + part.length()));
        offset += part.length() + 1;
      }
      return chunks;
    });
    when(delegate.classifySequences(anyList())).thenAnswer(inv -> {
      List<String> texts = inv.getArgument(0);
      return texts
        .stream()
        .map(t -> new ClassifierResults(List.of(new ClassifierResult("toxic", t.length() / 10f))))
        .toList();
    });
    return delegate;
  }

  @Test
  void single_chunk_input_is_submitted_whole_and_keeps_the_no_span_shape() {
    var delegate = mockModel();
    var engine = new OnnxClassifierEngine(delegate, ClassifierMode.SEQUENCE, vertx);

    var response = engine.rxClassify(new ClassifyRequest("hello")).blockingGet();

    assertThat(response.topLabel()).isEqualTo("toxic");
    assertThat(response.topScore()).isEqualTo(0.5f);
    verify(delegate).classifySequences(List.of("hello"));
    verify(delegate, never()).infer(anyString());
  }

  @Test
  void split_input_tags_rows_with_chunk_spans_and_keeps_max_per_label() {
    var delegate = mockModel();
    var engine = new OnnxClassifierEngine(delegate, ClassifierMode.SEQUENCE, vertx);

    // Two chunks: "abc" [0,3) scored 0.3 and "abcdefgh" [4,12) scored 0.8
    var response = engine.rxClassify(new ClassifyRequest("abc|abcdefgh")).blockingGet();

    assertThat(response.topLabel()).isEqualTo("toxic");
    assertThat(response.topScore()).isEqualTo(0.8f);
    assertThat(response.results()).hasSize(2);
    assertThat(response.results().get(0).start()).isEqualTo(0);
    assertThat(response.results().get(0).end()).isEqualTo(3);
    assertThat(response.results().get(1).start()).isEqualTo(4);
    assertThat(response.results().get(1).end()).isEqualTo(12);
    verify(delegate, never()).infer(anyString());
  }

  /** Token stub: one "PER" row per chunk covering the whole chunk, spans relative to it. */
  private static void stubTokens(OnnxBertClassifierModel delegate) {
    when(delegate.classifyTokens(anyList())).thenAnswer(inv -> {
      List<String> texts = inv.getArgument(0);
      return texts
        .stream()
        .map(t ->
          new ClassifierResults(List.of(new ClassifierResult("PER", 0.9f, t, 0, t.length())))
        )
        .toList();
    });
  }

  @Test
  void token_mode_batches_chunks_and_shifts_spans_to_the_original_text() throws Exception {
    var delegate = mockModel();
    stubTokens(delegate);
    var engine = new OnnxClassifierEngine(delegate, ClassifierMode.TOKEN, vertx);

    // Two chunks: "John" [0,4) and "Paris" [5,10)
    var response = engine.rxClassify(new ClassifyRequest("John|Paris")).blockingGet();

    assertThat(response.topLabel()).isEqualTo("PER");
    assertThat(response.results())
      .extracting(r -> r.token() + "@" + r.start() + "-" + r.end())
      .containsExactly("John@0-4", "Paris@5-10");
    verify(delegate, never()).infer(anyString());
    verify(delegate, never()).classifySequences(anyList());

    engine.close();
    verify(delegate).close();
  }

  @Test
  void token_mode_batch_keeps_request_order() {
    var delegate = mockModel();
    stubTokens(delegate);
    var engine = new OnnxClassifierEngine(delegate, ClassifierMode.TOKEN, vertx);

    var responses = engine
      .rxClassifyBatch(List.of(new ClassifyRequest("John"), new ClassifyRequest("Paris")))
      .blockingGet();

    assertThat(responses)
      .extracting(r -> r.results().getFirst().token())
      .containsExactly("John", "Paris");
  }

  @Test
  void token_mode_on_blank_input_finds_nothing() {
    var delegate = mockModel();
    when(delegate.split(anyString())).thenReturn(List.of());
    var engine = new OnnxClassifierEngine(delegate, ClassifierMode.TOKEN, vertx);

    var response = engine.rxClassify(new ClassifyRequest("   ")).blockingGet();

    assertThat(response.results()).isEmpty();
    verify(delegate, never()).classifyTokens(anyList());
  }

  @Test
  void classify_batch_preserves_request_order() {
    var delegate = mockModel();
    var engine = new OnnxClassifierEngine(delegate, ClassifierMode.SEQUENCE, vertx);

    var requests = List.of(
      new ClassifyRequest("aa"),
      new ClassifyRequest("bbbb"),
      new ClassifyRequest("cccccc")
    );
    var responses = engine.rxClassifyBatch(requests, List.of()).blockingGet();

    // Response i belongs to request i (score encodes the text length) regardless of batch fusion.
    assertThat(responses).hasSize(3);
    assertThat(responses.get(0).topScore()).isEqualTo(0.2f);
    assertThat(responses.get(1).topScore()).isEqualTo(0.4f);
    assertThat(responses.get(2).topScore()).isEqualTo(0.6f);
  }
}
