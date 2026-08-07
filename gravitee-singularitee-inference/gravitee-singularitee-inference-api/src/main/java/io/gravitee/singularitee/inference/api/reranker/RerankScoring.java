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
package io.gravitee.singularitee.inference.api.reranker;

/**
 * Scoring transformation for cross-encoder reranker output.
 *
 * @author Remi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum RerankScoring {
  /** Apply sigmoid to produce a 0..1 score. Default for [batch, 1] outputs. */
  SIGMOID,
  /** Apply softmax and take the positive class. Default for [batch, 2] outputs. */
  SOFTMAX,
  /** Return raw logit - monotonic ordering only, no normalization. */
  LOGIT,
}
