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
package io.gravitee.singularitee.workspace.config;

import java.util.List;
import java.util.Map;

/**
 * llama.cpp decision model configuration.
 *
 * <p>Plain configuration, never on the wire. Numeric zero means "engine default".
 *
 * @param nCtx           context size per decider
 * @param nBatch         batch size; for the hidden-state families also the longest prompt
 * @param nSeqMax        label families: sequences per decider, one of which holds the shared prefix
 * @param nParallel      deciders in the pool, each with its own context
 * @param nGpuLayers     layers offloaded to the GPU
 * @param loraPath       LoRA adapter file, empty for none
 * @param custom         whether this is any causal model scored on {@link #codes}
 * @param codes          custom models: label codes, one per option position; empty for the default
 * @param reverse        custom models: score each choice in both option orders, averaged
 * @param promptSource   {@code model}, {@code chat}, {@code none} or {@code template}
 * @param promptTemplate the template source when {@code promptSource} is {@code template}
 * @param prefill        {@code chat} only: text appended after the generation prompt; empty for
 *                       the default
 * @param temperatures   overrides of the model's calibration temperatures
 * @param classifier     the question classifying asks, or {@code null} when the model is not
 *                       also published as a classifier
 */
public record LlamaCppDecisionConfig(
  int nCtx,
  int nBatch,
  int nSeqMax,
  int nParallel,
  int nGpuLayers,
  String loraPath,
  boolean custom,
  List<String> codes,
  boolean reverse,
  String promptSource,
  String promptTemplate,
  String prefill,
  Map<String, Float> temperatures,
  DecisionClassifierQuestion classifier
) {
  /** The model's own {@code systemone} template. */
  public static final String SOURCE_MODEL = "model";
  /** The model's chat template around the shipped decision instruction. */
  public static final String SOURCE_CHAT = "chat";
  /** No template: caller-rendered prompts only. */
  public static final String SOURCE_NONE = "none";
  /** A {@code templates:} entry, materialised into {@link #promptTemplate}. */
  public static final String SOURCE_TEMPLATE = "template";

  public LlamaCppDecisionConfig {
    loraPath = loraPath == null ? "" : loraPath;
    codes = codes == null ? List.of() : List.copyOf(codes);
    promptSource = promptSource == null || promptSource.isBlank()
      ? (custom ? SOURCE_NONE : SOURCE_MODEL)
      : promptSource;
    promptTemplate = promptTemplate == null ? "" : promptTemplate;
    prefill = prefill == null ? "" : prefill;
    temperatures = temperatures == null ? Map.of() : Map.copyOf(temperatures);
  }

  /** Returns a copy whose prompt is the given workspace template. */
  public LlamaCppDecisionConfig withTemplate(String source) {
    return new LlamaCppDecisionConfig(
      nCtx,
      nBatch,
      nSeqMax,
      nParallel,
      nGpuLayers,
      loraPath,
      custom,
      codes,
      reverse,
      SOURCE_TEMPLATE,
      source,
      prefill,
      temperatures,
      classifier
    );
  }
}
