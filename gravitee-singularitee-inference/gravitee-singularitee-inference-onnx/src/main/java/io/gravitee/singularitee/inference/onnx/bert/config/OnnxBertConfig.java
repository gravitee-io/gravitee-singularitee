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
package io.gravitee.singularitee.inference.onnx.bert.config;

import static io.gravitee.singularitee.inference.api.Constants.DEFAULT_TOKENIZER_CONFIG;

import io.gravitee.singularitee.inference.api.utils.ConfigWrapper;
import io.gravitee.singularitee.inference.math.api.GioMaths;
import io.gravitee.singularitee.inference.onnx.OnnxConfig;
import io.gravitee.singularitee.inference.onnx.bert.resource.OnnxBertResource;
import java.util.Map;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class OnnxBertConfig extends OnnxConfig<OnnxBertResource> {

  private final Map<String, String> tokenizerConfig;

  private final GioMaths gioMath;
  private final ConfigWrapper configuration;

  public OnnxBertConfig(
    OnnxBertResource resource,
    GioMaths gioMath,
    Map<String, Object> configuration,
    Map<String, String> tokenizerConfig
  ) {
    super(resource);
    this.configuration = new ConfigWrapper(configuration);
    this.gioMath = gioMath;
    this.tokenizerConfig = tokenizerConfig;
  }

  public OnnxBertConfig(
    OnnxBertResource resource,
    GioMaths gioMath,
    Map<String, Object> onnxConfig
  ) {
    this(resource, gioMath, onnxConfig, DEFAULT_TOKENIZER_CONFIG);
  }

  public <T> T get(String key) {
    return this.configuration.get(key);
  }

  public <T> T get(String key, T defaultValue) {
    return this.configuration.get(key, defaultValue);
  }

  public GioMaths gioMath() {
    return gioMath;
  }

  public Map<String, String> getTokenizerConfig() {
    return tokenizerConfig;
  }
}
