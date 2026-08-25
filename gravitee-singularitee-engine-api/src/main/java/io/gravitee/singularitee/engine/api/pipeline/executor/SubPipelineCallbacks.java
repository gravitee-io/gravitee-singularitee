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
package io.gravitee.singularitee.engine.api.pipeline.executor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of the callbacks a sub-pipeline step executes through: the local pipeline
 * executor (registered once the executor exists, which is after the step executors are
 * built) and the remote executors keyed by remote id. Handed to executors through the
 * services record, so no executor needs post-construction mutation.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class SubPipelineCallbacks {

  private volatile PipelineExecutorCallback local;
  private final Map<String, PipelineExecutorCallback> remotes = new ConcurrentHashMap<>();

  /** Registers the local executor and the remote executors (replacing any previous set). */
  public void register(
    PipelineExecutorCallback local,
    Map<String, PipelineExecutorCallback> remoteCallbacks
  ) {
    this.local = local;
    this.remotes.clear();
    if (remoteCallbacks != null) {
      this.remotes.putAll(remoteCallbacks);
    }
  }

  /** The local executor, or {@code null} before registration. */
  public PipelineExecutorCallback local() {
    return local;
  }

  /** The remote executors keyed by remote id; never null. */
  public Map<String, PipelineExecutorCallback> remotes() {
    return Map.copyOf(remotes);
  }

  /** The remote executor registered for {@code id}, or {@code null}. */
  public PipelineExecutorCallback remote(String id) {
    return remotes.get(id);
  }
}
