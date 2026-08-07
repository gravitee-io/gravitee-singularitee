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
package io.gravitee.singularitee.standalone.node;

import io.gravitee.common.component.LifecycleComponent;
import io.gravitee.node.cache.NodeCacheService;
import io.gravitee.node.cluster.NodeClusterService;
import io.gravitee.node.container.AbstractNode;
import io.gravitee.node.management.http.ManagementService;
import io.gravitee.node.monitoring.handler.NodeMonitoringEventHandler;
import io.gravitee.node.monitoring.healthcheck.NodeHealthCheckService;
import io.gravitee.node.monitoring.infos.NodeInfosService;
import io.gravitee.node.monitoring.monitor.NodeGpuMonitorService;
import io.gravitee.node.monitoring.monitor.NodeMonitorService;
import io.gravitee.node.monitoring.monitor.gpu.GpuMonitorEventHandler;
import io.gravitee.node.opentelemetry.exporter.SpanExporterFactory;
import io.gravitee.node.plugins.service.ServiceManager;
import io.gravitee.node.reporter.ReporterManager;
import io.gravitee.plugin.core.api.PluginRegistry;
import io.gravitee.plugin.core.internal.PluginEventListener;
import io.gravitee.singularitee.standalone.vertx.GrpcServerComponent;
import io.gravitee.singularitee.standalone.vertx.HttpApiServerComponent;
import io.gravitee.singularitee.standalone.vertx.WorkspaceLoaderComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node implementation for Singularitee.
 *
 * <p>Extends the standard gravitee-node lifecycle with two AI-specific components:
 * <ol>
 *   <li>{@link WorkspaceLoaderComponent} — loads models and pipelines from a workspace YAML before
 *       the gRPC port opens</li>
 *   <li>{@link GrpcServerComponent} — starts the Vert.x gRPC server as the last component</li>
 * </ol>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SingulariteeNode extends AbstractNode {

  public static final String APPLICATION_NAME = "gio-singularitee";

  @Override
  public String name() {
    return "Gravitee.io - Singularitee";
  }

  @Override
  public String application() {
    return APPLICATION_NAME;
  }

  @Override
  public List<Class<? extends LifecycleComponent>> components() {
    final List<Class<? extends LifecycleComponent>> components = new ArrayList<>();

    components.add(SpanExporterFactory.class);
    components.add(NodeClusterService.class);
    components.add(ServiceManager.class);
    components.add(ManagementService.class);
    components.add(NodeMonitoringEventHandler.class);
    components.add(NodeInfosService.class);
    components.add(NodeHealthCheckService.class);
    components.add(NodeMonitorService.class);
    // GPU monitoring (nvidia-smi based). The event handler (consumer) is registered before the
    // collector (producer) so it is subscribed on the event bus before the first snapshot is
    // published. Both self-gate: NodeGpuMonitorService no-ops unless services.monitoring.gpu.enabled=true,
    // and GpuMonitorEventHandler only binds Micrometer gauges (gpu_*) when services.metrics.enabled=true.
    components.add(GpuMonitorEventHandler.class);
    components.add(NodeGpuMonitorService.class);
    // Start the servers FIRST so they bind and answer /health before any model is loaded.
    // They listen on their Vert.x event loops while the (blocking) workspace loader pulls in
    // models afterwards; requests for not-yet-loaded models simply return model_not_found.
    components.add(GrpcServerComponent.class);

    // Native HTTP API (no-op unless http.enabled=true). After gRPC so the shared tracer
    // (started by GrpcServerComponent) is already running.
    components.add(HttpApiServerComponent.class);

    // Load the workspace (models + pipelines) AFTER the servers are listening, so the server
    // is reachable (and /health is green) while models load in the background.
    components.add(WorkspaceLoaderComponent.class);

    return components;
  }

  @Override
  public Map<String, Object> metadata() {
    return new ConcurrentHashMap<>();
  }
}
