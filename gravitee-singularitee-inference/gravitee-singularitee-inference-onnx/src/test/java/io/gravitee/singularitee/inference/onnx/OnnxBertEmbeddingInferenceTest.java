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

import static io.gravitee.singularitee.inference.api.Constants.MAX_SEQUENCE_LENGTH_DEFAULT_VALUE;
import static io.gravitee.singularitee.inference.api.Constants.POOLING_MODE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gravitee.singularitee.inference.api.Constants;
import io.gravitee.singularitee.inference.api.embedding.EmbeddingTokenCount;
import io.gravitee.singularitee.inference.api.embedding.PoolingMode;
import io.gravitee.singularitee.inference.math.api.GioMaths;
import io.gravitee.singularitee.inference.math.simd.LoopBoundSIMDMath;
import io.gravitee.singularitee.inference.math.simd.MaskAwareSIMDMath;
import io.gravitee.singularitee.inference.math.vanilla.NativeMath;
import io.gravitee.singularitee.inference.onnx.bert.config.OnnxBertConfig;
import io.gravitee.singularitee.inference.onnx.bert.embedding.OnnxBertEmbeddingModel;
import io.gravitee.singularitee.inference.onnx.bert.resource.OnnxBertResource;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.commons.math3.util.FastMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class OnnxBertEmbeddingInferenceTest extends OnnxBertBaseTest {

  // ONNX EMBEDDING
  protected static final String SBERT_TOKENIZER_PATH = "sbert/all-minilm-l6-v2-q-tokenizer.json";
  protected static final String SBERT_MODEL_ONNX_PATH = "sbert/all-minilm-l6-v2-q.onnx";

  protected static final String XENOVA_SBERT_ONNX =
    HF_URL + "Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx";
  protected static final String XENOVA_SBERT_TOKENIZER =
    HF_URL + "Xenova/all-MiniLM-L6-v2/resolve/main/tokenizer.json";

  private static final URI HF_TOKENIZER = getUriIfExist(
    SBERT_TOKENIZER_PATH,
    XENOVA_SBERT_TOKENIZER
  );
  private static final URI MODEL_ONNX = getUriIfExist(SBERT_MODEL_ONNX_PATH, XENOVA_SBERT_ONNX);

  private static final OnnxBertResource ONNX_BERT_RESOURCE = new OnnxBertResource(
    Path.of(MODEL_ONNX),
    Path.of(HF_TOKENIZER)
  );
  public static final Map<String, Object> CLS_ONNX_CONFIG = Map.of(
    POOLING_MODE,
    PoolingMode.CLS,
    Constants.MAX_SEQUENCE_LENGTH,
    MAX_SEQUENCE_LENGTH_DEFAULT_VALUE
  );
  private static final List<OnnxBertConfig> ONNX_BERT_CONFIG_STREAM_CLS = List.of(
    new OnnxBertConfig(ONNX_BERT_RESOURCE, NativeMath.INSTANCE, CLS_ONNX_CONFIG),
    new OnnxBertConfig(ONNX_BERT_RESOURCE, MaskAwareSIMDMath.INSTANCE, CLS_ONNX_CONFIG),
    new OnnxBertConfig(ONNX_BERT_RESOURCE, LoopBoundSIMDMath.INSTANCE, CLS_ONNX_CONFIG)
  );

  public static final Map<String, Object> MEAN_ONNX_CONFIG = Map.of(
    POOLING_MODE,
    PoolingMode.MEAN,
    Constants.MAX_SEQUENCE_LENGTH,
    MAX_SEQUENCE_LENGTH_DEFAULT_VALUE
  );
  private static final List<OnnxBertConfig> ONNX_BERT_CONFIG_STREAM_MEAN = List.of(
    new OnnxBertConfig(ONNX_BERT_RESOURCE, NativeMath.INSTANCE, MEAN_ONNX_CONFIG),
    new OnnxBertConfig(ONNX_BERT_RESOURCE, MaskAwareSIMDMath.INSTANCE, MEAN_ONNX_CONFIG),
    new OnnxBertConfig(ONNX_BERT_RESOURCE, LoopBoundSIMDMath.INSTANCE, MEAN_ONNX_CONFIG)
  );

  public static final String THE_BIG_BROWN_FOX_JUMPED_OVER_THE_LAZY_DOG =
    "The big brown fox jumped over the lazy dog";
  public static final String THE_BROWN_FOX_JUMPED_OVER_THE_DOG =
    "The brown fox jumped over the dog";

  public static Stream<OnnxBertConfig> params_that_must_return_embedding() {
    return Stream.of(
      ONNX_BERT_CONFIG_STREAM_CLS.stream(),
      ONNX_BERT_CONFIG_STREAM_MEAN.stream()
    ).flatMap(Function.identity());
  }

  public static Stream<OnnxBertConfig> params_that_must_return_embedding_with_mean() {
    return ONNX_BERT_CONFIG_STREAM_MEAN.stream();
  }

  @Test
  public void embedding_must_be_the_same_with_different_maths() {
    var nativeModel = new OnnxBertEmbeddingModel(ONNX_BERT_CONFIG_STREAM_MEAN.get(0));
    var simdModel = new OnnxBertEmbeddingModel(ONNX_BERT_CONFIG_STREAM_MEAN.get(1));
    var simdLoopBoundModel = new OnnxBertEmbeddingModel(ONNX_BERT_CONFIG_STREAM_MEAN.get(2));

    var resultNative = nativeModel.infer(THE_BIG_BROWN_FOX_JUMPED_OVER_THE_LAZY_DOG);
    assertEquals(384, resultNative.embedding().length);
    assertEquals(11, resultNative.tokenCount());

    var resultSimdMask = simdModel.infer(THE_BIG_BROWN_FOX_JUMPED_OVER_THE_LAZY_DOG);
    assertEquals(384, resultSimdMask.embedding().length);
    assertEquals(11, resultSimdMask.tokenCount());

    var resultSimdLoopBound = simdLoopBoundModel.infer(THE_BIG_BROWN_FOX_JUMPED_OVER_THE_LAZY_DOG);
    assertEquals(384, resultSimdLoopBound.embedding().length);
    assertEquals(11, resultSimdLoopBound.tokenCount());

    assertArrayEquals(resultNative.embedding(), resultSimdMask.embedding());
    assertArrayEquals(resultSimdMask.embedding(), resultSimdLoopBound.embedding());

    nativeModel.close();
    simdModel.close();
    simdLoopBoundModel.close();
  }

  @ParameterizedTest
  @MethodSource("params_that_must_return_embedding")
  public void must_return_embedding(OnnxBertConfig config) {
    var modelInference = new OnnxBertEmbeddingModel(config);
    var result = modelInference.infer(THE_BIG_BROWN_FOX_JUMPED_OVER_THE_LAZY_DOG);
    assertEquals(384, result.embedding().length);
    assertEquals(11, result.tokenCount());
    modelInference.close();
  }

  @Test
  public void must_embed_across_multiple_partitions_when_input_exceeds_partition_size() {
    // Force a tiny partition so a normal sentence (9 content tokens) spans several windows,
    // exercising the chunked [CLS] window [SEP] path. The previous implementation re-ran the
    // model on the whole input once per partition and left trailing null partitions.
    var smallPartitionConfig = new OnnxBertConfig(
      ONNX_BERT_RESOURCE,
      NativeMath.INSTANCE,
      Map.of(POOLING_MODE, PoolingMode.MEAN, Constants.MAX_SEQUENCE_LENGTH, 8)
    );
    var modelInference = new OnnxBertEmbeddingModel(smallPartitionConfig);

    var result = modelInference.infer(THE_BIG_BROWN_FOX_JUMPED_OVER_THE_LAZY_DOG);

    assertEquals(384, result.embedding().length);
    assertEquals(11, result.tokenCount());
    assertEquals(
      1.0f,
      smallPartitionConfig.gioMath().cosineScore(result.embedding(), result.embedding())
    );

    modelInference.close();
  }

  @ParameterizedTest
  @MethodSource("params_that_must_return_embedding_with_mean")
  public void must_perform_cosine_score(OnnxBertConfig config) {
    var modelInference = new OnnxBertEmbeddingModel(config);
    var result1 = modelInference.infer(THE_BIG_BROWN_FOX_JUMPED_OVER_THE_LAZY_DOG);
    var result2 = modelInference.infer(THE_BROWN_FOX_JUMPED_OVER_THE_DOG);

    testResults(config.gioMath(), result1, result2);
    modelInference.close();
  }

  @ParameterizedTest
  @MethodSource("params_that_must_return_embedding_with_mean")
  public void must_infer_all(OnnxBertConfig config) {
    var modelInference = new OnnxBertEmbeddingModel(config);
    var list = modelInference.inferAll(
      List.of(THE_BIG_BROWN_FOX_JUMPED_OVER_THE_LAZY_DOG, THE_BROWN_FOX_JUMPED_OVER_THE_DOG)
    );

    assertEquals(2, list.size());

    var result1 = list.getFirst();
    var result2 = list.getLast();

    testResults(config.gioMath(), result1, result2);
    modelInference.close();
  }

  @Test
  public void encodePooled_and_combine_must_match_infer() {
    // Tiny budget so the input splits into several chunks — the batch entry points must
    // reproduce infer()'s vector exactly (same encodeAll + pool + weighted-combine path).
    var smallPartitionConfig = new OnnxBertConfig(
      ONNX_BERT_RESOURCE,
      NativeMath.INSTANCE,
      Map.of(POOLING_MODE, PoolingMode.MEAN, Constants.MAX_SEQUENCE_LENGTH, 8)
    );
    var modelInference = new OnnxBertEmbeddingModel(smallPartitionConfig);

    var chunks = modelInference.split(THE_BIG_BROWN_FOX_JUMPED_OVER_THE_LAZY_DOG);
    assertTrue(chunks.size() > 1, "expected a multi-chunk split at budget 8");

    var pooled = modelInference.encodePooled(
      chunks
        .stream()
        .map(io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter.Chunk::text)
        .toList()
    );
    assertEquals(chunks.size(), pooled.size());

    var combined = modelInference.combine(pooled);
    var direct = modelInference.infer(THE_BIG_BROWN_FOX_JUMPED_OVER_THE_LAZY_DOG);

    assertArrayEquals(direct.embedding(), combined.embedding());
    // combine() reports content tokens (no [CLS]/[SEP]); infer() keeps the full-input count
    assertEquals(9, combined.tokenCount());

    modelInference.close();
  }

  private static void testResults(
    GioMaths gioMath,
    EmbeddingTokenCount result1,
    EmbeddingTokenCount result2
  ) {
    assertEquals(384, result1.embedding().length);
    assertEquals(11, result1.tokenCount());

    assertEquals(384, result2.embedding().length);
    assertEquals(9, result2.tokenCount());

    assertEquals(1.0f, gioMath.cosineScore(result1.embedding(), result1.embedding()));
    assertEquals(1.0f, gioMath.cosineScore(result2.embedding(), result2.embedding()));

    // exact kernel output drifts across ORT releases; assert similarity, not bit-parity
    assertEquals(0.91, gioMath.cosineScore(result1.embedding(), result2.embedding()), 0.01);
  }
}
