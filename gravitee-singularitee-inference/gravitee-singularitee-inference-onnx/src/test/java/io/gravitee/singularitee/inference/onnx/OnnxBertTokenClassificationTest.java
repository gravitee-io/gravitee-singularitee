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

import static io.gravitee.singularitee.inference.api.Constants.*;
import static io.gravitee.singularitee.inference.api.classifier.ClassifierMode.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gravitee.singularitee.inference.api.classifier.ClassifierResults;
import io.gravitee.singularitee.inference.math.vanilla.NativeMath;
import io.gravitee.singularitee.inference.onnx.bert.classifier.OnnxBertClassifierModel;
import io.gravitee.singularitee.inference.onnx.bert.config.OnnxBertConfig;
import io.gravitee.singularitee.inference.onnx.bert.resource.OnnxBertResource;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class OnnxBertTokenClassificationTest extends OnnxBertBaseTest {

  protected static final String TOKEN_TOKENIZER_JSON = "tarekziade-distilbert-NER/tokenizer.json";
  protected static final String TOKEN_MODEL_ONNX = "tarekziade-distilbert-NER/model.onnx";
  protected static final String TOKEN_CONFIG_JSON = "tarekziade-distilbert-NER/config.json";

  private static final String DSLIM_BERT_BASE_NER_RESOLVE_MAIN =
    "tarekziade/distilbert-NER/resolve/main/";
  private static final String HF_DISTILBERT_NER_TOKEN_PATH =
    DSLIM_BERT_BASE_NER_RESOLVE_MAIN + "onnx/";

  protected static final String HF_DISTILBERT_NER_ONNX_DOWNLOAD =
    HF_URL + HF_DISTILBERT_NER_TOKEN_PATH + "model_quantized.onnx";
  protected static final String HF_DISTILBERT_NER_TOKENIZER =
    HF_URL + DSLIM_BERT_BASE_NER_RESOLVE_MAIN + "tokenizer.json";
  protected static final String HF_DISTILBERT_NER_CONFIG_JSON =
    HF_URL + DSLIM_BERT_BASE_NER_RESOLVE_MAIN + "config.json";

  private static final URI TKN_MODEL_ONNX = getUriIfExist(
    TOKEN_MODEL_ONNX,
    HF_DISTILBERT_NER_ONNX_DOWNLOAD
  );
  private static final URI TKN_HF_TOKENIZER = getUriIfExist(
    TOKEN_TOKENIZER_JSON,
    HF_DISTILBERT_NER_TOKENIZER
  );
  private static final URI TKN_MODEL_CONFIG_JSON = getUriIfExist(
    TOKEN_CONFIG_JSON,
    HF_DISTILBERT_NER_CONFIG_JSON
  );

  private static final OnnxBertResource TOKEN_ONNX_BERT_RESOURCE = new OnnxBertResource(
    Path.of(TKN_MODEL_ONNX),
    Path.of(TKN_HF_TOKENIZER),
    Path.of(TKN_MODEL_CONFIG_JSON)
  );

  private static final OnnxBertClassifierModel tokenModel = new OnnxBertClassifierModel(
    new OnnxBertConfig(
      TOKEN_ONNX_BERT_RESOURCE,
      NativeMath.INSTANCE,
      Map.of(CLASSIFIER_MODE, TOKEN, DISCARDED_LABELS, List.of("O", "B-MISC", "I-MISC"))
    )
  );

  @Test
  public void must_classify_tokens_in_sentence() {
    var results = tokenModel
      .infer("My name is Laura and I live in Houston, Texas")
      .results()
      .stream()
      .toList();

    assertEquals(3, results.size());

    var token1 = results.getFirst();
    assertEquals("Laura", token1.token());
    assertEquals("B-PER", token1.label());
    assertTrue(token1.score() >= 0.95);
    assertEquals(11, token1.start());
    assertEquals(16, token1.end());

    var token2 = results.get(1);
    assertEquals("Houston", token2.token());
    assertEquals("B-LOC", token2.label());
    assertTrue(token2.score() >= 0.95);
    assertEquals(31, token2.start());
    assertEquals(38, token2.end());

    var token3 = results.getLast();
    assertEquals("Texas", token3.token());
    assertEquals("B-LOC", token3.label());
    assertTrue(token3.score() >= 0.95);
    assertEquals(40, token3.start());
    assertEquals(45, token3.end());
  }

  @Test
  public void must_classify_tokens_in_sentences() {
    var tokensList = tokenModel
      .inferAll(
        List.of(
          "My name is Laura and I live in Houston, Texas",
          "My name is Clara and I live in Berkley, California"
        )
      )
      .stream()
      .map(ClassifierResults::results)
      .toList();

    assertEquals(2, tokensList.size());

    var firstSentence = tokensList.getFirst().stream().toList();
    assertEquals(3, firstSentence.size());

    var token1 = firstSentence.getFirst();
    assertEquals("Laura", token1.token());
    assertEquals("B-PER", token1.label());
    assertTrue(token1.score() >= 0.95);
    assertEquals(11, token1.start());
    assertEquals(16, token1.end());

    var token2 = firstSentence.get(1);
    assertEquals("Houston", token2.token());
    assertEquals("B-LOC", token2.label());
    assertTrue(token2.score() >= 0.95);
    assertEquals(31, token2.start());
    assertEquals(38, token2.end());

    var token3 = firstSentence.getLast();
    assertEquals("Texas", token3.token());
    assertEquals("B-LOC", token3.label());
    assertTrue(token3.score() >= 0.95);
    assertEquals(40, token3.start());
    assertEquals(45, token3.end());

    var secondSentence = tokensList.getLast().stream().toList();
    assertEquals(5, secondSentence.size());
    token1 = secondSentence.getFirst();
    assertEquals("Clara", token1.token());
    assertEquals("B-PER", token1.label());
    assertTrue(token1.score() >= 0.95);
    assertEquals(11, token1.start());
    assertEquals(16, token1.end());

    token2 = secondSentence.get(1);
    assertEquals("Be", token2.token());
    assertEquals("B-LOC", token2.label());
    assertTrue(token2.score() >= 0.95);
    assertEquals(31, token2.start());
    assertEquals(33, token2.end());

    token2 = secondSentence.get(2);
    assertEquals("##rk", token2.token());
    assertEquals("B-LOC", token2.label());
    assertTrue(token2.score() >= 0.95);
    assertEquals(33, token2.start());
    assertEquals(35, token2.end());

    token2 = secondSentence.get(3);
    assertEquals("##ley", token2.token());
    assertEquals("B-LOC", token2.label());
    assertTrue(token2.score() >= 0.95);
    assertEquals(35, token2.start());
    assertEquals(38, token2.end());

    token2 = secondSentence.getLast();
    assertEquals("California", token2.token());
    assertEquals("B-LOC", token2.label());
    assertTrue(token2.score() >= 0.95);
    assertEquals(40, token2.start());
    assertEquals(50, token2.end());
  }

  @Test
  public void must_keep_original_offsets_when_input_is_split_into_chunks() {
    String sentence = "My name is Laura and I live in Houston, Texas";

    // tiny budget forces the sentence to span several chunks, so detected spans live in chunks
    // whose start offset is non-zero — exercising the offset re-mapping in combineToken
    var chunkedModel = new OnnxBertClassifierModel(
      new OnnxBertConfig(
        TOKEN_ONNX_BERT_RESOURCE,
        NativeMath.INSTANCE,
        Map.of(
          CLASSIFIER_MODE,
          TOKEN,
          DISCARDED_LABELS,
          List.of("O", "B-MISC", "I-MISC"),
          MAX_SEQUENCE_LENGTH,
          8
        )
      )
    );

    var results = chunkedModel.infer(sentence).results().stream().toList();

    // every returned span must map back to the matching text in the ORIGINAL sentence; this holds
    // only if each chunk's local offsets were shifted by that chunk's start offset
    for (var token : results) {
      assertTrue(
        token.start() >= 0 && token.end() <= sentence.length() && token.start() < token.end(),
        "span out of bounds: " + token
      );
      assertEquals(
        token.token().replace("##", ""),
        sentence.substring(token.start(), token.end()),
        "offset does not map back to the token text: " + token
      );
    }

    // the clearly-named entities survive splitting with their original character offsets, including
    // "Houston" which lands in a later chunk (non-zero start offset)
    assertTrue(
      results.stream().anyMatch(t -> t.token().equals("Laura") && t.start() == 11 && t.end() == 16),
      "Laura not found at its original offset: " + results
    );
    assertTrue(
      results
        .stream()
        .anyMatch(t -> t.token().equals("Houston") && t.start() == 31 && t.end() == 38),
      "Houston not found at its original offset: " + results
    );

    chunkedModel.close();
  }

  @AfterAll
  public static void cleanup() {
    tokenModel.close();
  }
}
