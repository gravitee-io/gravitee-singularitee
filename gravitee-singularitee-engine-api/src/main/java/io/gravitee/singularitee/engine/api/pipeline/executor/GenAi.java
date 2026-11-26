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
package io.gravitee.singularitee.engine.api.pipeline.executor;

/**
 * OpenTelemetry {@code gen_ai.*} semantic-convention keys, emitted verbatim on model spans. Like
 * the OpenInference keys they are a published contract and never carry the name prefix.
 */
public final class GenAi {

  private GenAi() {}

  public static final String OPERATION_NAME = "gen_ai.operation.name";
  public static final String REQUEST_MODEL = "gen_ai.request.model";
  public static final String USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
  public static final String USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";

  public static final String OPERATION_CHAT = "chat";
  public static final String OPERATION_EMBEDDINGS = "embeddings";
}
