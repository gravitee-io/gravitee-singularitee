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
package io.gravitee.singularitee.http.translation.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.gravitee.singularitee.protocol.TodoItem;

/** One plan item inside the progress event; {@code proof} is omitted when absent. */
public record ProgressTodo(
  String id,
  String title,
  String status,
  @JsonInclude(JsonInclude.Include.NON_NULL) String proof
) {
  public ProgressTodo(TodoItem t) {
    this(t.getId(), t.getTitle(), t.getStatus(), t.getProof().isEmpty() ? null : t.getProof());
  }
}
