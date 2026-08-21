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
package io.gravitee.singularitee.inference.api.embedding;

/**
 * Formats text before feeding it to an embedding model.
 *
 * <p>Some embedding models are instruction-aware and expect the raw text wrapped in a prompt
 * (an instruction line, or a {@code "query: "} / {@code "passage: "} prefix); others, such as
 * BGE-M3, take the text as is.
 *
 * <p>This is a functional interface; any lambda works:
 * <pre>{@code
 * // Raw text, no wrapping
 * EmbeddingTemplate plain = text -> text;
 *
 * // Instruction-prefixed retrieval query
 * EmbeddingTemplate query = text ->
 *     "Instruct: Given a web search query, retrieve relevant passages.\nQuery: " + text;
 * }</pre>
 *
 * @author Remi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@FunctionalInterface
public interface EmbeddingTemplate {
  /**
   * Wraps the raw input text into the model-specific format.
   *
   * @param text The raw text to embed
   * @return The formatted input string to pass to the tokenizer
   */
  String format(String text);

  /**
   * Identity template: passes text through unchanged, for models that take raw text.
   */
  EmbeddingTemplate IDENTITY = text -> text;
}
