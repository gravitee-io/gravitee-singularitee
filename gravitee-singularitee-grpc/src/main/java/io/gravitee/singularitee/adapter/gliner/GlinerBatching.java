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
package io.gravitee.singularitee.adapter.gliner;

import io.gravitee.singularitee.adapter.batching.BatchingConfig;

/**
 * GLiNER micro-batching knobs: a {@link BatchingConfig} read once from the environment under the
 * {@code GRAVITEE_GLINER_BATCH} prefix ({@code _MAX}, {@code _MAX_TOKENS}, {@code _BUCKET_TOKENS},
 * {@code _LINGER_MS} — see {@link BatchingConfig} for semantics and defaults).
 */
final class GlinerBatching {

  static final BatchingConfig CONFIG = BatchingConfig.fromEnv("GRAVITEE_GLINER_BATCH");

  private GlinerBatching() {}
}
