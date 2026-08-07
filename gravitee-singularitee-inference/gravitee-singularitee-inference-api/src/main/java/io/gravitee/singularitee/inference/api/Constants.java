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

import static java.util.Collections.singletonMap;

import java.util.Map;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class Constants {

  public static final int MAX_SEQUENCE_LENGTH_DEFAULT_VALUE = 510; // 512 - 2 (special tokens [CLS] and [SEP])

  private Constants() {}

  public static final String INPUT = "input";

  /*
    Resource Constants
  */
  public static final String MODEL_PATH = "modelPath";
  public static final String TOKENIZER_PATH = "tokenizerPath";
  public static final String CONFIG_JSON_PATH = "configJsonPath";
  public static final String MODEL_NAME = "modelName";

  /*
    Llama.cpp payload keys
  */
  public static final String CONTEXT = "context";
  public static final String CONTEXT_N_CTX = "nCtx";
  public static final String CONTEXT_N_BATCH = "nBatch";
  public static final String CONTEXT_N_UBATCH = "nUBatch";
  public static final String CONTEXT_N_SEQ_MAX = "nSeqMax";
  public static final String CONTEXT_N_THREADS = "nThreads";
  public static final String CONTEXT_N_THREADS_BATCH = "nThreadsBatch";
  public static final String CONTEXT_POOLING_TYPE = "poolingType";
  public static final String CONTEXT_ATTENTION_TYPE = "attentionType";
  public static final String CONTEXT_FLASH_ATTN_TYPE = "flashAttnType";
  public static final String CONTEXT_OFFLOAD_KQV = "offloadKQV";
  public static final String CONTEXT_NO_PERF = "noPerf";
  public static final String MODEL_PARAMS = "modelParams";
  public static final String MODEL_N_GPU_LAYERS = "nGpuLayers";
  public static final String MODEL_USE_MLOCK = "useMlock";
  public static final String MODEL_USE_MMAP = "useMmap";
  public static final String MODEL_SPLIT_MODE = "splitMode";
  public static final String MODEL_MAIN_GPU = "mainGpu";
  public static final String MODEL_LOG_LEVEL = "logLevel";
  public static final String MODEL_LORA_REPO = "loraRepo";
  public static final String MODEL_LORA_REPO_PATH = "loraPath";
  public static final String MODEL_MMPROJ_PATH = "mmprojPath";
  public static final String MODEL_MULTIMODALITY = "multimodality";
  public static final String MODEL_RPC_SERVERS = "rpcServers";
  public static final String MEDIA = "media";
  public static final String SEQ_ID = "seqId";
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
  public static final Map<String, String> DEFAULT_TOKENIZER_CONFIG = singletonMap(PADDING, "false");
  public static final String MAX_SEQUENCE_LENGTH = "maxSequenceLength";

  // Token-classification overlap (in tokens) between sliding windows; >= longest expected entity
  // so an entity sliced at one window's edge is whole inside the next.
  public static final String TOKEN_WINDOW_OVERLAP = "tokenWindowOverlap";
  public static final int TOKEN_WINDOW_OVERLAP_DEFAULT_VALUE = 64;

  /*
   Inference Service
  */

  public static final String SERVICE_INFERENCE_MODELS_ADDRESS = "service:inference:models";
  public static final String SERVICE_INFERENCE_MODELS_INFER_TEMPLATE =
    "service:inference:models:%s";
  public static final String INFERENCE_TYPE = "inferenceType";
  public static final String INFERENCE_FORMAT = "inferenceFormat";
  public static final String MODEL_ADDRESS_KEY = "modelAddress";
}
