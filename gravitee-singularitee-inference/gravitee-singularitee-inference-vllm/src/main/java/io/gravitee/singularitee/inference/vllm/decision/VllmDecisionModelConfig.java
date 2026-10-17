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
package io.gravitee.singularitee.inference.vllm.decision;

import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import java.nio.file.Path;

/**
 * How to load a vLLM Semantic Router decision model. {@code null} keeps the runtime's default.
 *
 * @param model           a built-in Hugging Face id ({@code vllm-sr/Decision-2.0-Kai-0.6B}) or a
 *                        local package directory
 * @param family          the model's family
 * @param revision        a Hub revision instead of the runtime's pin
 * @param device          {@code auto}, {@code cpu}, {@code cuda}, {@code cuda:1}, {@code mps}...
 * @param profile         {@code exact} or a faster profile the model enables
 * @param engine          {@code auto}, {@code native} or {@code onnxruntime}
 * @param memoryBudgetGib device memory the model may use
 * @param threads         CPU threads the runtime may use
 * @param cacheDir        where the runtime downloads model files
 * @param offline         load from {@code cacheDir} only
 * @param venvPath        the venv holding {@code vllm-srun}, or {@code null} to auto-detect
 * @param hfToken         Hugging Face token for gated repositories, or {@code null}
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record VllmDecisionModelConfig(
  String model,
  DecisionFamily family,
  String revision,
  String device,
  String profile,
  String engine,
  Double memoryBudgetGib,
  Integer threads,
  String cacheDir,
  Boolean offline,
  Path venvPath,
  String hfToken
) {}
