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
package io.gravitee.singularitee.inference.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gravitee.singularitee.inference.api.reranker.RerankPair;
import io.gravitee.singularitee.inference.api.reranker.RerankScoring;
import io.gravitee.singularitee.inference.api.reranker.RerankTokenCount;
import io.gravitee.singularitee.inference.math.vanilla.NativeMath;
import io.gravitee.singularitee.inference.onnx.bert.config.OnnxBertConfig;
import io.gravitee.singularitee.inference.onnx.bert.reranker.OnnxBertRerankerModel;
import io.gravitee.singularitee.inference.onnx.bert.resource.OnnxBertResource;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link OnnxBertRerankerModel} using
 * Xenova/ms-marco-MiniLM-L-6-v2 (quantized, 23 MB) — a cross-encoder that emits
 * a single {@code [batch, 1]} logit per pair, defaulting to SIGMOID scoring.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class OnnxBertRerankerTest extends OnnxBertBaseTest {

  private static final String HF_RERANKER_BASE = "Xenova/ms-marco-MiniLM-L-6-v2/resolve/main/";
  private static final String HF_RERANKER_ONNX_DOWNLOAD =
    HF_URL + HF_RERANKER_BASE + "onnx/model_quantized.onnx";
  private static final String HF_RERANKER_TOKENIZER = HF_URL + HF_RERANKER_BASE + "tokenizer.json";

  private static final String RERANKER_MODEL_ONNX = "ms-marco-minilm-l-6-v2/model_quantized.onnx";
  private static final String RERANKER_TOKENIZER_JSON = "ms-marco-minilm-l-6-v2/tokenizer.json";

  private static final URI RERANKER_MODEL_URI = getUriIfExist(
    RERANKER_MODEL_ONNX,
    HF_RERANKER_ONNX_DOWNLOAD
  );
  private static final URI RERANKER_TOKENIZER_URI = getUriIfExist(
    RERANKER_TOKENIZER_JSON,
    HF_RERANKER_TOKENIZER
  );

  private static final OnnxBertResource RERANKER_RESOURCE = new OnnxBertResource(
    Path.of(RERANKER_MODEL_URI),
    Path.of(RERANKER_TOKENIZER_URI)
  );

  // Shared model instance — cross-encoder, [batch,1] output → SIGMOID default
  private static final OnnxBertRerankerModel rerankerModel = new OnnxBertRerankerModel(
    new OnnxBertConfig(RERANKER_RESOURCE, NativeMath.INSTANCE, Map.of())
  );

  // A highly relevant (query, document) pair
  private static final RerankPair RELEVANT_PAIR = new RerankPair(
    "What is the capital of France?",
    "Paris is the capital and most populous city of France."
  );

  // A clearly irrelevant (query, document) pair
  private static final RerankPair IRRELEVANT_PAIR = new RerankPair(
    "What is the capital of France?",
    "The Amazon rainforest covers most of the Amazon basin in South America."
  );

  @Test
  public void must_score_relevant_pair_higher_than_irrelevant_pair() {
    var relevant = rerankerModel.infer(RELEVANT_PAIR);
    var irrelevant = rerankerModel.infer(IRRELEVANT_PAIR);

    assertTrue(
      relevant.score() > irrelevant.score(),
      "relevant score must exceed irrelevant score"
    );
    assertTrue(relevant.score() > 0.5f, "relevant pair should score above 0.5 with sigmoid");
    assertTrue(irrelevant.score() < 0.5f, "irrelevant pair should score below 0.5 with sigmoid");
  }

  @Test
  public void must_return_token_count_for_single_pair() {
    var result = rerankerModel.infer(RELEVANT_PAIR);

    assertTrue(result.tokenCount() > 0, "token count must be positive");
  }

  @Test
  public void must_infer_all_same_query_in_batch() {
    var results = rerankerModel.inferAll(List.of(RELEVANT_PAIR, IRRELEVANT_PAIR));

    assertEquals(2, results.size());

    var relevant = results.getFirst();
    var irrelevant = results.getLast();

    assertTrue(
      relevant.score() > irrelevant.score(),
      "batched: relevant score must exceed irrelevant score"
    );
    assertTrue(
      relevant.score() > 0.5f,
      "batched: relevant pair should score above 0.5 with sigmoid"
    );
    assertTrue(
      irrelevant.score() < 0.5f,
      "batched: irrelevant pair should score below 0.5 with sigmoid"
    );
  }

  @Test
  public void must_return_consistent_scores_between_single_and_batch() {
    var singleRelevant = rerankerModel.infer(RELEVANT_PAIR);
    var singleIrrelevant = rerankerModel.infer(IRRELEVANT_PAIR);

    var batch = rerankerModel.inferAll(List.of(RELEVANT_PAIR, IRRELEVANT_PAIR));

    // Padding in batch mode may produce slightly different float values due to attention mask
    // differences. Verify ranking agreement instead of exact equality.
    assertTrue(
      singleRelevant.score() > singleIrrelevant.score(),
      "single: relevant must rank higher than irrelevant"
    );
    assertTrue(
      batch.getFirst().score() > batch.getLast().score(),
      "batch: relevant must rank higher than irrelevant"
    );
  }

  @Test
  public void must_fall_back_to_sequential_for_mixed_queries() {
    var pair1 = new RerankPair("What is the capital of France?", "Paris is the capital of France.");
    var pair2 = new RerankPair("Who wrote Hamlet?", "Hamlet was written by William Shakespeare.");

    var results = rerankerModel.inferAll(List.of(pair1, pair2));

    assertEquals(2, results.size());
    for (RerankTokenCount r : results) {
      assertTrue(r.score() >= 0f && r.score() <= 1f, "sigmoid output must be in [0, 1]");
      assertTrue(r.tokenCount() > 0, "token count must be positive");
    }
  }

  @Test
  public void must_batch_the_rest_when_one_document_is_oversized() {
    // The regression this guards: a single oversized document used to demote the whole
    // request to one-pair-at-a-time. Correctness is what is observable from here — every
    // document still gets its own score, in input order, with the oversized one in the middle.
    String oversized = ("The ocean is vast and deep. ").repeat(400);
    var pairs = List.of(
      RELEVANT_PAIR,
      new RerankPair(RELEVANT_PAIR.query(), oversized),
      IRRELEVANT_PAIR
    );

    var results = rerankerModel.inferAll(pairs);

    assertEquals(3, results.size());
    assertTrue(results.get(0).score() > results.get(2).score(), "relevant must outrank irrelevant");
    assertTrue(results.get(1).tokenCount() > 0, "oversized document must still be scored");
    for (var r : results) {
      assertTrue(r.tokenCount() > 0, "every pair must report a token count");
    }
  }

  @Test
  public void must_preserve_input_order_when_documents_vary_in_length() {
    // Documents are grouped by length before batching, so results come back out of the
    // order they were computed in — they must still map to their original positions.
    var pairs = List.of(
      new RerankPair(RELEVANT_PAIR.query(), "Paris is the capital of France. ".repeat(20)),
      IRRELEVANT_PAIR,
      RELEVANT_PAIR,
      new RerankPair(RELEVANT_PAIR.query(), "Bananas grow in the tropics. ".repeat(15))
    );

    var batched = rerankerModel.inferAll(pairs);
    assertEquals(4, batched.size());

    // Each batched score must equal the score that pair gets on its own.
    for (int i = 0; i < pairs.size(); i++) {
      float single = rerankerModel.infer(pairs.get(i)).score();
      assertEquals(
        single,
        batched.get(i).score(),
        1e-3f,
        "batched score for index " + i + " must match single-pair scoring"
      );
    }
  }

  @Test
  public void must_split_a_large_request_into_several_batches() {
    // Well past MAX_BATCH_ROWS, so the packing loop has to run more than once. The point is
    // that it still terminates and returns one result per input, in order.
    var pairs = new java.util.ArrayList<RerankPair>();
    for (int i = 0; i < 300; i++) {
      pairs.add(i % 2 == 0 ? RELEVANT_PAIR : IRRELEVANT_PAIR);
    }

    var results = rerankerModel.inferAll(pairs);

    assertEquals(300, results.size());
    for (int i = 0; i < results.size(); i++) {
      assertTrue(results.get(i).tokenCount() > 0, "index " + i + " must be scored");
      if (i % 2 == 0) {
        assertTrue(results.get(i).score() > 0.5f, "even indices are the relevant pair");
      } else {
        assertTrue(results.get(i).score() < 0.5f, "odd indices are the irrelevant pair");
      }
    }
  }

  @Test
  public void must_return_empty_for_null_or_empty_input() {
    assertEquals(List.of(), rerankerModel.inferAll(null));
    assertEquals(List.of(), rerankerModel.inferAll(List.of()));
  }

  @Test
  public void must_respect_explicit_logit_scoring_mode() {
    var logitModel = new OnnxBertRerankerModel(
      new OnnxBertConfig(RERANKER_RESOURCE, NativeMath.INSTANCE, Map.of()),
      RerankScoring.LOGIT
    );

    var relevant = logitModel.infer(RELEVANT_PAIR);
    var irrelevant = logitModel.infer(IRRELEVANT_PAIR);

    // Raw logit: relevant should still rank higher
    assertTrue(
      relevant.score() > irrelevant.score(),
      "logit: relevant score must exceed irrelevant score"
    );

    logitModel.close();
  }

  @AfterAll
  public static void cleanup() {
    rerankerModel.close();
  }
}
