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
package io.gravitee.singularitee.inference.api.memory;

/**
 * Controls what happens when a pre-flight VRAM check determines that the model
 * may not fit in available GPU memory.
 *
 * <ul>
 *   <li>{@link #FAIL}     — abort loading and throw {@link InsufficientVramException}.</li>
 *   <li>{@link #WARN}     — log a warning and continue loading (default).</li>
 *   <li>{@link #DISABLED} — skip the memory check entirely.</li>
 * </ul>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum MemoryCheckPolicy {
  FAIL,
  WARN,
  DISABLED,
}
