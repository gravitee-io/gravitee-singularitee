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
package io.gravitee.singularitee.plugin.api;

import io.gravitee.node.api.cache.CacheManager;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.singularitee.engine.api.pipeline.TodoSessionStore;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.SubPipelineCallbacks;
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateRenderer;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.inference.api.template.ChatTemplateRenderer;

/**
 * The parent-owned collaborators a plugin builds its executor from. Everything here
 * lives in the main classloader; a plugin never loads a model or a native library.
 *
 * @param executionContext model and stream resolution shared by every executor
 * @param templateRenderer the shared pipeline template renderer
 * @param chatTemplateRenderer the shared model chat-template renderer
 * @param modelRegistry    the loaded models
 * @param pipelineRegistry the registered pipelines
 * @param todoSessionStore cross-request todo persistence, or {@code null} when disabled
 * @param cacheManager     the node cache, or {@code null} when unavailable (client side)
 * @param subPipelineCallbacks the executors a sub-pipeline step runs through
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record StepExecutorServices(
  StepExecutionContext executionContext,
  TemplateRenderer templateRenderer,
  ChatTemplateRenderer chatTemplateRenderer,
  ModelRegistry modelRegistry,
  PipelineRegistry pipelineRegistry,
  TodoSessionStore todoSessionStore,
  CacheManager cacheManager,
  SubPipelineCallbacks subPipelineCallbacks,
  Configuration configuration
) {}
