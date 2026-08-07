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
package io.gravitee.singularitee.inference.llama.cpp;

import io.gravitee.llama.cpp.LlamaRuntime;
import io.gravitee.llama.cpp.nativelib.LlamaLibLoader;

/**
 * Initializes the llama.cpp native runtime.
 *
 * <p>Loads native shared libraries and calls {@code llama_backend_init()},
 * making all FFM bindings available. Thread-safe and idempotent.
 *
 * <p>Compute backend registration (CPU, Metal, RPC, etc.) is intentionally
 * left out — it is a per-model concern handled by {@link Model} based on
 * the {@link ModelConfig} (e.g. local-only vs RPC).
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlamaBackend {

  private static volatile boolean initialized;

  private LlamaBackend() {}

  /**
   * Ensures native libraries are loaded and the llama backend is initialized.
   * No-op if already called.
   */
  public static void init() {
    if (initialized) {
      return;
    }
    synchronized (LlamaBackend.class) {
      if (initialized) {
        return;
      }
      LlamaLibLoader.load();
      LlamaRuntime.llama_backend_init();
      initialized = true;
    }
  }
}
