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

import io.gravitee.singularitee.engine.ClassifyRequest;
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

/**
 * Micro-batched SEQUENCE-mode chunk handling for the ONNX classifier engine (mocked delegate).
 */
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

  @Test
  void token_mode_keeps_the_windowed_single_call_path() throws Exception {
    var delegate = mockModel();
    when(delegate.infer(anyString())).thenReturn(
      new ClassifierResults(List.of(new ClassifierResult("PER", 0.9f)))
    );
    var engine = new OnnxClassifierEngine(delegate, ClassifierMode.TOKEN, vertx);

    var response = engine.rxClassify(new ClassifyRequest("John")).blockingGet();

    assertThat(response.topLabel()).isEqualTo("PER");
    verify(delegate).infer("John");
    verify(delegate, never()).classifySequences(anyList());

    engine.close(); // no batcher in TOKEN mode — must not throw
    verify(delegate).close();
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
