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
package io.gravitee.singularitee.standalone.sync;

import java.util.List;

/**
 * Heartbeat body sent to Gamma, in the shape of its {@code InstanceStatusRequest}. It doubles as
 * the instance registration: the first report creates the instance. Anything the node cannot
 * measure is {@code null}. {@code ready} is false while the workspace is still loading. {@code gpuName} and {@code gpuMemoryMb} summarize the accelerators for the fit check;
 * {@code hardware} carries the detail.
 */
public record NodeStatus(
  String hostname,
  String nodeVersion,
  List<String> engines,
  String gpuName,
  Long gpuMemoryMb,
  Long ramTotalMb,
  Long ramFreeMb,
  long appliedRevision,
  List<ModelState> models,
  String lastError,
  HardwareInfo hardware,
  boolean ready,
  long uptimeSeconds,
  int heartbeatSeconds,
  List<PipelineState> pipelines
) {
  /**
   * State of one loaded model; the node only knows models that finished loading, so {@code READY}.
   *
   * @param engine the model type (llama_cpp, onnx_classifier...)
   * @param task what it does (text-generation, text-classification...)
   */
  public record ModelState(
    String id,
    String phase,
    Integer progressPercent,
    String error,
    String engine,
    String task
  ) {}

  /** A pipeline the node serves; {@code name} may be null and {@code status} is the registry's. */
  public record PipelineState(String id, String name, String task, String status, int steps) {}
}
