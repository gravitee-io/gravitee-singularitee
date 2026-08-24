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
package io.gravitee.singularitee.inference.api;

import java.util.Map;

/**
 * Payload and configuration keys shared by the inference engines and their adapters.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class Constants {

  /** Default encoder sequence budget: 512 minus the two special tokens [CLS] and [SEP]. */
  public static final int MAX_SEQUENCE_LENGTH_DEFAULT_VALUE = 510;

  private Constants() {}

  public static final String INPUT = "input";

  /*
    Text-generation payload keys
  */
  public static final String PROMPT = "prompt";
  public static final String MESSAGES = "messages";
  public static final String MAX_TOKENS = "maxTokens";
  public static final String TEMPERATURE = "temperature";
  public static final String TOP_P = "topP";
  public static final String PRESENCE_PENALTY = "presencePenalty";
  public static final String FREQUENCY_PENALTY = "frequencyPenalty";
  public static final String STOP = "stop";
  public static final String SEED = "seed";
  public static final String TOP_LOGPROBS = "topLogprobs";

  /*
    ONNX Constants
  */

  public static final String INPUT_IDS = "input_ids";
  public static final String ATTENTION_MASK = "attention_mask";
  public static final String TOKEN_TYPE_IDS = "token_type_ids";

  /*
    Classifier Constants
  */

  public static final String CLASSIFIER_MODE = "mode";
  public static final String CLASSIFIER_LABELS = "labels";
  public static final String DISCARDED_LABELS = "discardedLabels";

  /*
   Embedding Constants
  */

  public static final String POOLING_MODE = "poolingMode";

  public static final String PADDING = "padding";
  public static final Map<String, String> DEFAULT_TOKENIZER_CONFIG = Map.of(PADDING, "false");
  public static final String MAX_SEQUENCE_LENGTH = "maxSequenceLength";

  // Token-classification overlap (in tokens) between sliding windows; >= longest expected entity
  // so an entity sliced at one window's edge is whole inside the next.
  public static final String TOKEN_WINDOW_OVERLAP = "tokenWindowOverlap";
  public static final int TOKEN_WINDOW_OVERLAP_DEFAULT_VALUE = 64;
}
