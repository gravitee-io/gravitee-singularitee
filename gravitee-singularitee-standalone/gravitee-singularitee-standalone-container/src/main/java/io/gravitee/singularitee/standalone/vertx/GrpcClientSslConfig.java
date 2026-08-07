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

import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.singularitee.client.ClientTlsOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.TrustOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@code grpc.client.ssl.*} block — the outbound counterpart of {@code grpc.ssl.*}.
 *
 * <p>{@code grpc.ssl} is what this server <em>presents and demands</em> of callers;
 * {@code grpc.client.ssl} is what it presents when it <em>calls another server</em> through a
 * workspace {@code remote:} endpoint. Certificates are deployment facts, so they live here
 * rather than in the workspace, which only decides <em>which</em> endpoints use TLS
 * ({@code ssl: true}).
 *
 * <pre>{@code
 * grpc:
 *   client:
 *     ssl:
 *       trustAll: false                 # dev escape hatch; skips server verification
 *       verifyHostname: true            # certificate must match the host dialled
 *       truststore:                     # verify the peer (a private CA)
 *         type: PEM                     # PEM | JKS | PKCS12
 *         path: /certs/ca.crt
 *         password: changeit            # JKS/PKCS12 only
 *       keystore:                       # our own identity — this is what makes it mTLS
 *         type: PEM
 *         path: /certs/client.crt
 *         keyPath: /certs/client.key    # PEM only
 *         password: changeit            # JKS/PKCS12 only
 * }</pre>
 *
 * <p>Returns {@code null} when nothing is configured, which leaves the JVM default trust
 * store in charge — right for a publicly-trusted certificate, not for a private CA.
 */
final class GrpcClientSslConfig {

  private static final Logger LOGGER = LoggerFactory.getLogger(GrpcClientSslConfig.class);
  private static final String PREFIX = "grpc.client.ssl";

  private GrpcClientSslConfig() {}

  static ClientTlsOptions from(Configuration env) {
    boolean trustAll = env.getProperty(PREFIX + ".trustAll", Boolean.class, false);
    boolean verifyHostname = env.getProperty(PREFIX + ".verifyHostname", Boolean.class, true);
    TrustOptions trust = trustOptions(env);
    KeyCertOptions keyCert = keyCertOptions(env);

    if (!trustAll && trust == null && keyCert == null) {
      return null;
    }
    if (trustAll) {
      LOGGER.warn(
        "{}.trustAll is enabled — server certificates are not verified. Development only.",
        PREFIX
      );
    }
    LOGGER.info(
      "Outbound gRPC TLS configured: truststore={}, client certificate={} ({})",
      trust != null ? "set" : "JVM default",
      keyCert != null ? "set" : "none",
      keyCert != null ? "mutual TLS" : "one-way TLS"
    );
    return new ClientTlsOptions(trust, keyCert, trustAll, verifyHostname);
  }

  private static TrustOptions trustOptions(Configuration env) {
    String type = env.getProperty(PREFIX + ".truststore.type");
    String path = env.getProperty(PREFIX + ".truststore.path");
    if (type == null || path == null) {
      return null;
    }
    String password = env.getProperty(PREFIX + ".truststore.password");
    return switch (type.toUpperCase()) {
      case "PEM" -> new io.vertx.core.net.PemTrustOptions().addCertPath(path);
      case "JKS" -> new JksOptions().setPath(path).setPassword(password);
      case "PKCS12" -> new PfxOptions().setPath(path).setPassword(password);
      default -> throw new IllegalArgumentException(
        PREFIX + ".truststore.type must be PEM, JKS or PKCS12 but was: " + type
      );
    };
  }

  private static KeyCertOptions keyCertOptions(Configuration env) {
    String type = env.getProperty(PREFIX + ".keystore.type");
    String path = env.getProperty(PREFIX + ".keystore.path");
    if (type == null || path == null) {
      return null;
    }
    String password = env.getProperty(PREFIX + ".keystore.password");
    return switch (type.toUpperCase()) {
      case "PEM" -> {
        String keyPath = env.getProperty(PREFIX + ".keystore.keyPath");
        if (keyPath == null) {
          throw new IllegalArgumentException(
            PREFIX + ".keystore.keyPath is required when type is PEM"
          );
        }
        yield new io.vertx.core.net.PemKeyCertOptions().setCertPath(path).setKeyPath(keyPath);
      }
      case "JKS" -> new JksOptions().setPath(path).setPassword(password);
      case "PKCS12" -> new PfxOptions().setPath(path).setPassword(password);
      default -> throw new IllegalArgumentException(
        PREFIX + ".keystore.type must be PEM, JKS or PKCS12 but was: " + type
      );
    };
  }
}
