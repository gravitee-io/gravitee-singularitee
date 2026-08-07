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

import io.gravitee.singularitee.inference.math.vanilla.NativeMath;
import io.gravitee.singularitee.inference.onnx.bert.config.OnnxBertConfig;
import io.gravitee.singularitee.inference.onnx.bert.fillmask.FillMaskResult;
import io.gravitee.singularitee.inference.onnx.bert.fillmask.OnnxBertFillMaskInference;
import io.gravitee.singularitee.inference.onnx.bert.resource.OnnxBertResource;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class OnnxBertFillMaskInferenceTest extends OnnxBertBaseTest {

  private static final String FILL_MASK_TOKENIZER_JSON =
    "google-bert-bert-base-uncased/tokenizer.json";
  private static final String FILL_MASK_MODEL_ONNX = "google-bert-bert-base-uncased/model.onnx";

  private static final String FILL_MASK_FILL_MASK_PATH =
    "google-bert/bert-base-uncased/resolve/main/";

  protected static final String FILL_MASK_ONNX_DOWNLOAD =
    HF_URL + FILL_MASK_FILL_MASK_PATH + "model.onnx";
  protected static final String FILL_MASK_TOKENIZER_DOWNLOAD =
    HF_URL + FILL_MASK_FILL_MASK_PATH + "tokenizer.json";

  private static final URI MODEL_ONNX = getUriIfExist(
    FILL_MASK_MODEL_ONNX,
    FILL_MASK_ONNX_DOWNLOAD
  );
  private static final URI HF_TOKENIZER = getUriIfExist(
    FILL_MASK_TOKENIZER_JSON,
    FILL_MASK_TOKENIZER_DOWNLOAD
  );
  private static final OnnxBertResource ONNX_BERT_RESOURCE = new OnnxBertResource(
    Path.of(MODEL_ONNX),
    Path.of(HF_TOKENIZER)
  );

  @Test
  public void must_fill_mask_for_one_input() {
    var model = new OnnxBertFillMaskInference(
      new OnnxBertConfig(ONNX_BERT_RESOURCE, NativeMath.INSTANCE, Map.of())
    );
    assertEquals("paris", model.infer("The capital of France is [MASK].").getFirst().label());
    assertEquals("england", model.infer("The capital of [MASK] is London.").getFirst().label());
    assertEquals("capital", model.infer("The [MASK] of Canada is Toronto.").getFirst().label());
  }

  @Test
  public void must_fill_mask_for_inputs() {
    var model = new OnnxBertFillMaskInference(
      new OnnxBertConfig(ONNX_BERT_RESOURCE, NativeMath.INSTANCE, Map.of())
    );
    var expected = List.of("paris", "england", "capital");

    assertTrue(
      model
        .inferAll(
          List.of(
            "The capital of France is [MASK].",
            "The capital of [MASK] is London.",
            "The [MASK] of Canada is Toronto."
          )
        )
        .stream()
        .map(List::getFirst)
        .map(FillMaskResult::label)
        .allMatch(expected::contains)
    );

    model.close();
  }
}
