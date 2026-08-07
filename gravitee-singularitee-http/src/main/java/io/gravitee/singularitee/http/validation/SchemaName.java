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
package io.gravitee.singularitee.http.validation;

/** The request payload schemas, each a JSON Pointer into {@code /llm-schemas.json}. */
public enum SchemaName {
  CHAT("/$defs/chatCompletions"),
  COMPLETIONS("/$defs/completions"),
  RESPONSES("/$defs/responses"),
  EMBEDDINGS("/$defs/embeddings"),
  CLASSIFY("/$defs/classify"),
  SIMILARITY("/$defs/similarity"),
  RERANK("/$defs/rerank");

  private final String pointer;

  SchemaName(String pointer) {
    this.pointer = pointer;
  }

  public String pointer() {
    return pointer;
  }
}
