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
 * <p>Extends the standard gravitee-node lifecycle with the inference components, started in
 * this order: {@link GrpcServerComponent}, {@link HttpApiServerComponent}, then
 * {@link WorkspaceLoaderComponent}. The servers bind first so {@code /health} answers while
 * models load; the readiness gate returns {@code 503} until the workspace is in.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SingulariteeNode extends AbstractNode {

  /** Application id reported to gravitee-node (product name, tracer service name). */
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
    // Servers start before the workspace loader so they bind and answer /health while the
    // (blocking) model load runs; service calls get 503 until ReadinessState flips.
    components.add(GrpcServerComponent.class);

    // No-op unless http.enabled=true. After gRPC so the shared tracer is already started.
    components.add(HttpApiServerComponent.class);

    components.add(WorkspaceLoaderComponent.class);

    return components;
  }

  @Override
  public Map<String, Object> metadata() {
    return new ConcurrentHashMap<>();
  }
}
