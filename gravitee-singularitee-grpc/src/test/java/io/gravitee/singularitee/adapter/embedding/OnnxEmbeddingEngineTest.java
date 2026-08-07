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
package io.gravitee.singularitee.adapter.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.engine.EmbedRequest;
import io.gravitee.singularitee.inference.api.embedding.EmbeddingTokenCount;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter.Chunk;
import io.gravitee.singularitee.inference.onnx.bert.embedding.OnnxBertEmbeddingModel;
import io.gravitee.singularitee.inference.onnx.bert.embedding.OnnxBertEmbeddingModel.ChunkEmbedding;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Micro-batched chunk handling for the ONNX embedding engine (mocked delegate model).
 */
class OnnxEmbeddingEngineTest {

  private static Vertx vertx;

  @BeforeAll
  static void startVertx() {
    vertx = Vertx.vertx();
  }

  @AfterAll
  static void stopVertx() {
    vertx.close();
  }

  /** Delegate stub: split on '|', embed each chunk as [text.length()], combine = first vector + summed tokens. */
  private static OnnxBertEmbeddingModel mockModel() {
    var delegate = mock(OnnxBertEmbeddingModel.class);
    when(delegate.split(anyString())).thenAnswer(inv -> {
      String text = inv.getArgument(0);
      if (text.isBlank()) {
        return List.of();
      }
      List<Chunk> chunks = new ArrayList<>();
      int offset = 0;
      for (String part : text.split("\\|")) {
        chunks.add(new Chunk(part, offset, offset + part.length()));
        offset += part.length() + 1;
      }
      return chunks;
    });
    when(delegate.encodePooled(anyList())).thenAnswer(inv -> {
      List<String> texts = inv.getArgument(0);
      return texts
        .stream()
        .map(t -> new ChunkEmbedding(new float[] { t.length() }, t.length()))
        .toList();
    });
    when(delegate.combine(anyList())).thenAnswer(inv -> {
      List<ChunkEmbedding> pooled = inv.getArgument(0);
      int tokens = pooled.stream().mapToInt(ChunkEmbedding::contentTokens).sum();
      return new EmbeddingTokenCount(pooled.getFirst().vector(), tokens);
    });
    // full-input tokenize incl. special tokens — content chars + 2, mirroring [CLS]/[SEP]
    when(delegate.countTokens(anyString())).thenAnswer(
      inv -> ((String) inv.getArgument(0)).replace("|", "").length() + 2
    );
    return delegate;
  }

  @Test
  void multi_chunk_request_uses_the_batched_entry_point_and_combines() {
    var delegate = mockModel();
    var engine = new OnnxEmbeddingEngine(delegate, vertx);

    var response = engine.rxEmbed(new EmbedRequest("abc|defgh")).blockingGet();

    // token count = infer()'s contract: full-input tokenize incl. special tokens (3 + 5 + 2),
    // NOT combine()'s content-token sum; vector = first chunk's ([3])
    assertThat(response.tokenCount()).isEqualTo(10);
    assertThat(response.embedding()).containsExactly(3f);
    verify(delegate, never()).infer(anyString());
    verify(delegate, never()).inferAll(anyList());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(delegate).encodePooled(captor.capture());
    // Both chunks of this lone request rode a single batched run (nothing else was queued).
    assertThat(captor.getValue()).containsExactly("abc", "defgh");
  }

  @Test
  void an_input_that_already_fits_skips_the_splitter() {
    // split() tokenises the whole text again just to discover it does not need splitting.
    // When the measured count is already within the budget that answer is knowable, so the
    // pass is skipped — the observable contract being that split() is never consulted.
    var delegate = mockModel();
    when(delegate.sequenceBudget()).thenReturn(100);
    var engine = new OnnxEmbeddingEngine(delegate, vertx);

    var response = engine.rxEmbed(new EmbedRequest("abc")).blockingGet();

    verify(delegate, never()).split(anyString());
    verify(delegate, never()).infer(anyString());

    // Identical to what the splitting path yields for the same input: the whole text is
    // embedded as one chunk, and the count stays infer()'s full-input tokenize (3 + 2).
    assertThat(response.tokenCount()).isEqualTo(5);
    assertThat(response.embedding()).containsExactly(3f);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(delegate).encodePooled(captor.capture());
    assertThat(captor.getValue()).containsExactly("abc");
  }

  @Test
  void an_input_over_the_budget_still_splits() {
    // The fast path must not swallow inputs that genuinely need chunking.
    var delegate = mockModel();
    when(delegate.sequenceBudget()).thenReturn(4);
    var engine = new OnnxEmbeddingEngine(delegate, vertx);

    var response = engine.rxEmbed(new EmbedRequest("abc|defgh")).blockingGet();

    verify(delegate).split("abc|defgh");
    assertThat(response.tokenCount()).isEqualTo(10);
  }

  @Test
  void blank_input_falls_back_to_infer_semantics() {
    var delegate = mockModel();
    when(delegate.infer("   ")).thenReturn(new EmbeddingTokenCount(new float[0], 0));
    var engine = new OnnxEmbeddingEngine(delegate, vertx);

    var response = engine.rxEmbed(new EmbedRequest("   ")).blockingGet();

    assertThat(response.embedding()).isEmpty();
    verify(delegate).infer("   ");
    verify(delegate, never()).encodePooled(anyList());
  }

  @Test
  void embed_batch_preserves_input_order_and_never_uses_infer_all() {
    var delegate = mockModel();
    var engine = new OnnxEmbeddingEngine(delegate, vertx);

    var texts = List.of("aa", "bbbb", "cccccc", "d");
    var responses = engine.rxEmbedBatch(texts).blockingGet();

    // Response i belongs to text i (vector encodes the text length) regardless of batch fusion.
    assertThat(responses).hasSize(4);
    for (int i = 0; i < texts.size(); i++) {
      assertThat(responses.get(i).embedding()).containsExactly((float) texts.get(i).length());
    }
    verify(delegate, never()).inferAll(anyList());
    verify(delegate, never()).infer(anyString());
  }

  @Test
  void failed_batch_fails_the_request() {
    var delegate = mockModel();
    when(delegate.encodePooled(anyList())).thenThrow(new IllegalStateException("boom"));
    var engine = new OnnxEmbeddingEngine(delegate, vertx);

    assertThatThrownBy(() ->
      engine.rxEmbed(new EmbedRequest("abc")).blockingGet()
    ).hasMessageContaining("boom");
  }

  @Test
  void close_closes_batcher_and_delegate() throws Exception {
    var delegate = mockModel();
    var engine = new OnnxEmbeddingEngine(delegate, vertx);

    engine.close();

    verify(delegate).close();
    assertThatThrownBy(() ->
      engine.rxEmbed(new EmbedRequest("abc")).blockingGet()
    ).hasMessageContaining("closed");
  }
}
