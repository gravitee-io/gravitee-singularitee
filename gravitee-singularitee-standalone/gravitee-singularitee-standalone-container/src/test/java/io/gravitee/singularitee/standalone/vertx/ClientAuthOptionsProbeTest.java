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
package io.gravitee.singularitee.standalone.vertx;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.node.vertx.server.http.VertxHttpServerOptions;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.net.SelfSignedCertificate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Probes gravitee-node's option parsing directly: does {@code grpc.ssl.clientAuth}
 * survive from the environment into the Vert.x {@link io.vertx.core.http.HttpServerOptions}
 * the gRPC server is created from? This is the mechanism {@code GrpcServerComponent}
 * relies on for mTLS enforcement.
 */
class ClientAuthOptionsProbeTest {

  private static io.vertx.core.http.HttpServerOptions build(Map<String, Object> props) {
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("test", props));
    var options = VertxHttpServerOptions.builder()
      .defaultPort(9090)
      .prefix("grpc")
      .environment(env)
      .id("grpc")
      .build();
    var ssc = SelfSignedCertificate.create();
    return options.createHttpServerOptions(ssc.keyCertOptions(), ssc.trustOptions());
  }

  @Test
  void client_auth_required_reaches_the_vertx_options() {
    var opts = build(Map.of("grpc.secured", "true", "grpc.ssl.clientAuth", "REQUIRED"));

    assertThat(opts.getClientAuth()).isEqualTo(ClientAuth.REQUIRED);
  }

  @Test
  void client_auth_lowercase_required_also_binds() {
    var opts = build(Map.of("grpc.secured", "true", "grpc.ssl.clientAuth", "required"));

    assertThat(opts.getClientAuth()).isEqualTo(ClientAuth.REQUIRED);
  }

  @Test
  void unset_client_auth_stays_none() {
    var opts = build(Map.of("grpc.secured", "true"));

    assertThat(opts.getClientAuth()).isEqualTo(ClientAuth.NONE);
  }
}
