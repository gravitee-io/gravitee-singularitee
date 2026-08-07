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
package io.gravitee.singularitee.inference.onnx.bert.resource;

import io.gravitee.singularitee.inference.onnx.OnnxResource;
import java.nio.file.Path;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class OnnxBertResource extends OnnxResource {

  private final Path tokenizer;
  private final Path configJson;

  public OnnxBertResource(Path model, Path tokenizer) {
    this(model, tokenizer, null);
  }

  public OnnxBertResource(Path model, Path tokenizer, Path config) {
    super(model);
    this.tokenizer = tokenizer;
    this.configJson = config;
  }

  public Path getTokenizer() {
    return tokenizer;
  }

  public Path getConfigJson() {
    return configJson;
  }
}
