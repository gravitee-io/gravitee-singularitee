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
 * Input for a cross-encoder reranker: a {@code (query, document)} pair
 * to be scored as a single sequence {@code [CLS] query [SEP] document [SEP]}.
 *
 * @param query    the query text
 * @param document the candidate document text to score against the query
 */
public record RerankPair(String query, String document) {}
