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
package io.gravitee.singularitee.standalone.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.kubernetes.client.KubernetesClient;
import io.gravitee.kubernetes.client.api.ResourceQuery;
import io.gravitee.kubernetes.client.api.WatchQuery;
import io.gravitee.kubernetes.client.model.v1.Event;
import io.gravitee.kubernetes.client.model.v1.Watchable;
import io.gravitee.node.api.cluster.ClusterManager;
import io.gravitee.node.plugin.cluster.standalone.StandaloneClusterManager;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.rxjava3.core.Vertx;
import org.springframework.context.annotation.Bean;

/**
 * Main Spring configuration for Singularitee.
 *
 * <p>Replaces all manual wiring previously in {@code Singularitee.java}. All beans
 * are created with the managed Vert.x RxJava3 instance from gravitee-node's
 * {@code VertxConfiguration}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@org.springframework.context.annotation.Configuration
public class UtilsConfiguration {

  @Bean
  public KubernetesClient kubernetesClient() {
    return new KubernetesClient() {
      @Override
      public Maybe<Watchable> create(Watchable watchable) {
        return Maybe.empty();
      }

      @Override
      public <T> Maybe<T> get(ResourceQuery<T> resourceQuery) {
        return Maybe.empty();
      }

      @Override
      public <E extends Event<? extends Watchable>> Flowable<E> watch(WatchQuery<E> watchQuery) {
        return Flowable.empty();
      }
    };
  }

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
