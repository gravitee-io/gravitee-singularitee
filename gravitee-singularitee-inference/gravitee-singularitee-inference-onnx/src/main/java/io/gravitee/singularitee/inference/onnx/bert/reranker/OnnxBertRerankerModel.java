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
package io.gravitee.singularitee.inference.onnx.bert.reranker;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.Result;
import io.gravitee.singularitee.inference.api.reranker.RerankPair;
import io.gravitee.singularitee.inference.api.reranker.RerankScoring;
import io.gravitee.singularitee.inference.api.reranker.RerankTokenCount;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter.Chunk;
import io.gravitee.singularitee.inference.onnx.FloatTensor;
import io.gravitee.singularitee.inference.onnx.bert.OnnxBertInference;
import io.gravitee.singularitee.inference.onnx.bert.config.OnnxBertConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-encoder reranker model backed by a BERT classification head (e.g. BAAI/bge-reranker-v2-m3).
 *
 * <p>A cross-encoder takes a {@code (query, document)} pair as a single sequence and emits
 * a classification logit directly. It does not produce a pooled hidden-state vector, so it
 * cannot be used as an embedder. The input type is {@link RerankPair} (structured pair),
 * avoiding {@code UnsupportedOperationException}-style workarounds.
 *
 * <p>The inherited {@link #inferAll(List)} is overridden to batch all pairs that share
 * the same query into a single padded forward pass — matching the common rerank use case
 * of "one query, many documents". Pairs with different queries fall back to the default
 * sequential iteration.
 *
 * <p>Auto-detects output shape: {@code [batch, 1]} → SIGMOID default; {@code [batch, 2]} →
 * SOFTMAX default. Other shapes are rejected.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class OnnxBertRerankerModel extends OnnxBertInference<RerankPair, RerankTokenCount> {

  /**
   * Ceiling on a batch's padded token count ({@code rows x longest row}), which is what the
   * forward pass actually costs. Overridable because the right value depends on the device and
   * on what else shares it.
   */
  private static final String MAX_BATCH_TOKENS_KEY = "GRAVITEE_ONNX_RERANK_MAX_BATCH_TOKENS";

  private static final int DEFAULT_MAX_BATCH_TOKENS = 32_768;

  /**
   * Hard cap on rows per batch, independent of length. Guards the degenerate case of very short
   * documents, where the padded-token budget alone would allow an enormous row count.
   */
  private static final int MAX_BATCH_ROWS = 256;

  private final RerankScoring scoring;

  private final int maxBatchTokens = resolveMaxBatchTokens();

  public OnnxBertRerankerModel(OnnxBertConfig config, RerankScoring scoring) {
    super(config);
    this.scoring = scoring;
  }

  private static int resolveMaxBatchTokens() {
    String raw = System.getenv(MAX_BATCH_TOKENS_KEY);
    if (raw == null) {
      raw = System.getenv(MAX_BATCH_TOKENS_KEY.toLowerCase(java.util.Locale.ENGLISH));
    }
    if (raw != null) {
      try {
        int parsed = Integer.parseInt(raw.trim());
        if (parsed > 0) {
          return parsed;
        }
      } catch (NumberFormatException ignored) {
        // fall through to the default
      }
    }
    return DEFAULT_MAX_BATCH_TOKENS;
  }

  /** Convenience constructor: {@code null} scoring = auto-default based on output shape. */
  public OnnxBertRerankerModel(OnnxBertConfig config) {
    this(config, null);
  }

  @Override
  public RerankTokenCount infer(RerankPair input) {
    List<String> docChunks = splitDocument(input.query(), input.document());

    // common case: the (query, document) pair fits in a single sequence
    if (docChunks.size() <= 1) {
      var enc = encodePair(input.query(), input.document());
      try (Result result = enc.result()) {
        float[] scores = extractScores(result);
        int tokens = enc.encoding().get(0).getIds().length;
        return new RerankTokenCount(scores[0], tokens);
      } catch (OrtException e) {
        throw new IllegalArgumentException(e);
      }
    }

    // long document: score every (query, chunk) pair in one batched pass. A document is as
    // relevant as its most relevant passage, so the pair score is the max over chunks.
    var enc = encodeAllPairs(input.query(), docChunks);
    try (Result result = enc.result()) {
      float[] scores = extractScores(result);
      float best = config.gioMath().max(scores);
      int tokens = 0;
      for (int i = 0; i < docChunks.size(); i++) {
        tokens += enc.encoding().get(i).getIds().length;
      }
      return new RerankTokenCount(best, tokens);
    } catch (OrtException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Splits {@code document} so each {@code (query, chunk)} pair fits the sequence budget, reserving
   * room for the (assumed short) query and the pair's extra separator token.
   */
  private List<String> splitDocument(String query, String document) {
    return splitDocument(countTokens(query), document);
  }

  /** As above, with the query already measured — it is the same for every document in a batch. */
  private List<String> splitDocument(int queryTokens, String document) {
    int docBudget = Math.max(sequenceBudget - queryTokens - 1, 1);
    return newSplitter(docBudget).split(document).stream().map(Chunk::text).toList();
  }

  /**
   * Batched inference for reranking.
   *
   * <p>The shape of a rerank request is one query against many documents, so the documents that
   * fit are encoded together and sent to ONNX in as few forward passes as the budget allows.
   *
   * <p>Three things this deliberately does <em>not</em> do:
   * <ul>
   *   <li><strong>Fall back wholesale.</strong> A single oversized document used to demote the
   *       entire request to one-pair-at-a-time — 100 forward passes instead of one. Oversized
   *       documents are now scored individually (each splitting and batching internally) while
   *       everything else still goes through the batched path.</li>
   *   <li><strong>Build one unbounded batch.</strong> The padded tensor costs
   *       {@code rows x longest}, and the row count comes straight from the caller, so a
   *       thousand-document rerank was a thousand-row forward pass. Batches are now capped on
   *       that padded-token product.</li>
   *   <li><strong>Ignore length spread.</strong> Padding is to the longest row, so one 512-token
   *       document dragged every other row up with it. Documents are grouped by length before
   *       packing, which keeps the padding close to the real content.</li>
   * </ul>
   *
   * <p>Results are returned in input order regardless of the order they were computed in.
   */
  @Override
  public List<RerankTokenCount> inferAll(List<RerankPair> input) {
    if (input == null || input.isEmpty()) {
      return List.of();
    }

    String query = input.getFirst().query();
    if (!input.stream().allMatch(p -> query.equals(p.query()))) {
      // Different queries cannot share a padded batch: score each on its own.
      return input.stream().map(this::infer).toList();
    }

    // Measured once — it used to be recomputed for every document, twice over
    // (documentFits and splitDocument).
    int queryTokens = countTokens(query);
    int docBudget = Math.max(sequenceBudget - queryTokens - 1, 1);

    RerankTokenCount[] out = new RerankTokenCount[input.size()];
    List<int[]> fitting = new ArrayList<>(input.size()); // {index, docTokens}

    for (int i = 0; i < input.size(); i++) {
      RerankPair pair = input.get(i);
      int docTokens = countTokens(pair.document());
      if (docTokens <= docBudget) {
        fitting.add(new int[] { i, docTokens });
      } else {
        // Oversized: infer() splits it and takes the best-scoring passage.
        out[i] = infer(pair);
      }
    }

    // Ascending length, so each packed batch holds documents of similar size and
    // pads to something close to their real length.
    fitting.sort(java.util.Comparator.comparingInt(e -> e[1]));

    int cursor = 0;
    while (cursor < fitting.size()) {
      int rows = 0;
      int longest = 0;
      int end = cursor;
      while (end < fitting.size()) {
        int candidateLongest = Math.max(longest, fitting.get(end)[1] + queryTokens + 1);
        int candidateCost = (rows + 1) * candidateLongest;
        // Always take at least one row, otherwise a single large document stalls.
        if (rows > 0 && (candidateCost > maxBatchTokens || rows >= MAX_BATCH_ROWS)) {
          break;
        }
        longest = candidateLongest;
        rows++;
        end++;
      }

      List<String> documents = new ArrayList<>(rows);
      for (int k = cursor; k < end; k++) {
        documents.add(input.get(fitting.get(k)[0]).document());
      }

      var enc = encodeAllPairs(query, documents);
      try (Result result = enc.result()) {
        float[] scores = extractScores(result);
        for (int k = 0; k < documents.size(); k++) {
          int index = fitting.get(cursor + k)[0];
          out[index] = new RerankTokenCount(scores[k], enc.encoding().get(k).getIds().length);
        }
      } catch (OrtException e) {
        throw new IllegalArgumentException(e);
      }
      cursor = end;
    }

    return List.of(out);
  }

  private float[] extractScores(Result result) throws OrtException {
    var tensor = FloatTensor.of(result.get(0));
    if (tensor.shape().length != 2) {
      throw new IllegalArgumentException(
        "Reranker output must be [batch, N] float32 — got rank " + tensor.shape().length
      );
    }
    float[][] logits = tensor.rows(0, tensor.dim(0), tensor.dim(1));
    if (logits.length == 0) return new float[0];
    int numClasses = logits[0].length;
    if (numClasses != 1 && numClasses != 2) {
      throw new IllegalArgumentException(
        "Reranker output must have 1 or 2 classes per row, got: " + numClasses
      );
    }

    RerankScoring mode = scoring != null
      ? scoring
      : (numClasses == 1 ? RerankScoring.SIGMOID : RerankScoring.SOFTMAX);

    return switch (mode) {
      case SIGMOID -> handleSigmoid(logits, numClasses);
      case SOFTMAX -> handleSoftmax(logits, numClasses);
      case LOGIT -> handleLogit(logits, numClasses);
    };
  }

  private float[] handleSigmoid(float[][] logits, int numClasses) {
    float[] out = new float[logits.length];
    int col = (numClasses == 1) ? 0 : 1;
    for (int i = 0; i < logits.length; i++) {
      out[i] = logits[i][col];
    }
    return config.gioMath().sigmoid(out);
  }

  private float[] handleSoftmax(float[][] logits, int numClasses) {
    float[] out = new float[logits.length];
    if (numClasses != 2) {
      throw new IllegalArgumentException(
        "SOFTMAX scoring requires [batch, 2] output, got " + numClasses
      );
    }
    for (int i = 0; i < logits.length; i++) {
      out[i] = config.gioMath().softmax(logits[i])[1];
    }
    return out;
  }

  private static float[] handleLogit(float[][] logits, int numClasses) {
    float[] out = new float[logits.length];
    for (int i = 0; i < logits.length; i++) {
      out[i] = (numClasses == 1) ? logits[i][0] : logits[i][1] - logits[i][0];
    }
    return out;
  }
}
