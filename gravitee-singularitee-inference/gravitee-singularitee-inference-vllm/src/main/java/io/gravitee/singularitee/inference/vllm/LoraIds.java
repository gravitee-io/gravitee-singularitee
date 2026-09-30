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
package io.gravitee.singularitee.inference.vllm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stable vLLM identities for LoRA adapters, one per adapter path for the engine's lifetime.
 *
 * <p>vLLM identifies an adapter by its {@code lora_int_id}: the scheduler admits at most
 * {@code max_loras} distinct ids per batch, and a worker that already holds an id does not load it
 * again. An id must therefore follow the adapter, not the sequence: one id per concurrent sequence
 * would serialize every sequence sharing an adapter, and an id reused for another adapter would be
 * served with the previous adapter's weights.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class LoraIds {

  /** An adapter's name and id inside vLLM. */
  record Assigned(String name, int id) {}

  private final Map<String, Assigned> byPath = new ConcurrentHashMap<>();
  private final Map<String, Integer> idByName = new ConcurrentHashMap<>();
  private final AtomicInteger nextId = new AtomicInteger(1); // lora_int_id must be >= 1

  /**
   * The identity of the adapter at {@code path}, assigned on first sight. The first name given for a path
   * is kept; a name already bound to another path gets the id appended, since vLLM expects one name per id.
   *
   * @param name the requested adapter name, or null/blank for a generated one
   */
  Assigned assign(String name, String path) {
    return byPath.computeIfAbsent(path.strip(), p -> {
      int id = nextId.getAndIncrement();
      String base = name == null || name.isBlank() ? "lora-" + id : name;
      String resolved = idByName.putIfAbsent(base, id) == null ? base : base + "-" + id;
      idByName.putIfAbsent(resolved, id);
      return new Assigned(resolved, id);
    });
  }
}
