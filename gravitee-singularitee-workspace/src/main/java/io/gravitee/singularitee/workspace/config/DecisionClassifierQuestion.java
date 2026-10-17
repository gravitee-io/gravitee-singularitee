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

import java.util.Map;

/**
 * The choice question that makes a decision model a classifier: classifying a text asks it.
 *
 * @param instructions the question, or {@code null} for a generic one
 * @param options      label to description (or {@code null}), in prompt order; {@code null} when
 *                     every request sends its labels
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record DecisionClassifierQuestion(Object instructions, Map<String, Object> options) {}
