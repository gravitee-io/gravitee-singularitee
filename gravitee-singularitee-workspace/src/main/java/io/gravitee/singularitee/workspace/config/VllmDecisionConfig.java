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
 * vLLM decision model configuration: a vLLM Semantic Router model run by its own runtime
 * ({@code decision1}, {@code decision2}), or any causal model vLLM serves scored on label codes
 * ({@code custom}).
 *
 * <p>Plain configuration, never on the wire. {@code null} keeps the runtime's default.
 *
 * @param family          {@code decision1}, {@code decision2} or {@code custom}; {@code null}
 *                        reads it from the built-in model id
 * @param revision        runtime models: a Hub revision instead of the runtime's pin
 * @param device          runtime models: {@code auto}, {@code cpu}, {@code cuda}, {@code mps}...
 * @param profile         runtime models: {@code exact} or a faster profile the model enables
 * @param engine          runtime models: {@code auto}, {@code native} or {@code onnxruntime}
 * @param memoryBudgetGib runtime models: device memory the model may use
 * @param threads         runtime models: CPU threads the runtime may use
 * @param cacheDir        runtime models: where model files are downloaded; {@code null} for the
 *                        server's models directory
 * @param offline         runtime models: load from the cache only
 * @param classifier      the question classifying asks, or {@code null} when the model is not
 *                        also published as a classifier
 * @param codes           custom: label codes, one per option position; empty for {@code A..Z}
 * @param reverse         custom: score each choice in both option orders, averaged
 * @param promptSource    custom: {@code chat}, {@code none} or {@code template}
 * @param promptTemplate  custom: the template source when {@code promptSource} is {@code template}
 * @param prefill         custom, {@code chat} only: text after the generation prompt; empty for
 *                        the default
 * @param temperatures    custom: calibration temperatures, keyed like {@code choice.3_5}
 * @param vllm            custom: the vLLM engine configuration
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record VllmDecisionConfig(
  String family,
  String revision,
  String device,
  String profile,
  String engine,
  Double memoryBudgetGib,
  Integer threads,
  String cacheDir,
  Boolean offline,
  DecisionClassifierQuestion classifier,
  List<String> codes,
  boolean reverse,
  String promptSource,
  String promptTemplate,
  String prefill,
  Map<String, Float> temperatures,
  VllmConfig vllm
) {
  /** The family that scores any causal model on label codes. */
  public static final String FAMILY_CUSTOM = "custom";

  public VllmDecisionConfig {
    codes = codes == null ? List.of() : List.copyOf(codes);
    promptSource = promptSource == null || promptSource.isBlank()
      ? LlamaCppDecisionConfig.SOURCE_NONE
      : promptSource;
    promptTemplate = promptTemplate == null ? "" : promptTemplate;
    prefill = prefill == null ? "" : prefill;
    temperatures = temperatures == null ? Map.of() : Map.copyOf(temperatures);
  }

  /** A model of vLLM Semantic Router, with the runtime's options only. */
  public static VllmDecisionConfig runtime(
    String family,
    String revision,
    String device,
    String profile,
    String engine,
    Double memoryBudgetGib,
    Integer threads,
    String cacheDir,
    Boolean offline,
    DecisionClassifierQuestion classifier
  ) {
    return new VllmDecisionConfig(
      family,
      revision,
      device,
      profile,
      engine,
      memoryBudgetGib,
      threads,
      cacheDir,
      offline,
      classifier,
      null,
      false,
      null,
      null,
      null,
      null,
      null
    );
  }

  /** Whether this is any causal model scored on {@link #codes}. */
  public boolean custom() {
    return FAMILY_CUSTOM.equalsIgnoreCase(family);
  }

  /** Returns a copy whose prompt is the given workspace template. */
  public VllmDecisionConfig withTemplate(String source) {
    return new VllmDecisionConfig(
      family,
      revision,
      device,
      profile,
      engine,
      memoryBudgetGib,
      threads,
      cacheDir,
      offline,
      classifier,
      codes,
      reverse,
      LlamaCppDecisionConfig.SOURCE_TEMPLATE,
      source,
      prefill,
      temperatures,
      vllm
    );
  }
}
