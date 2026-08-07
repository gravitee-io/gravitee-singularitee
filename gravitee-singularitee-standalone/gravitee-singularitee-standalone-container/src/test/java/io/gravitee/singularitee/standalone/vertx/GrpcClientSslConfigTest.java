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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.node.api.configuration.Configuration;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Mapping of {@code grpc.client.ssl.*} onto outbound TLS material. */
class GrpcClientSslConfigTest {

  /** Minimal {@link Configuration} backed by a map. */
  private record MapConfig(Map<String, String> values) implements Configuration {
    @Override
    public boolean containsProperty(String key) {
      return values.containsKey(key);
    }

    @Override
    public String getProperty(String key) {
      return values.get(key);
    }

    @Override
    public String getProperty(String key, String defaultValue) {
      return values.getOrDefault(key, defaultValue);
    }

    @Override
    public <T> T getProperty(String key, Class<T> targetType) {
      return getProperty(key, targetType, null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
      String raw = values.get(key);
      if (raw == null) return defaultValue;
      if (targetType == Boolean.class) return (T) Boolean.valueOf(raw);
      if (targetType == Integer.class) return (T) Integer.valueOf(raw);
      return (T) raw;
    }
  }

  private static Configuration config(String... kv) {
    var m = new java.util.HashMap<String, String>();
    for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
    return new MapConfig(m);
  }

  @Test
  void unconfigured_is_null_so_the_jvm_default_trust_store_applies() {
    assertThat(GrpcClientSslConfig.from(config())).isNull();
  }

  @Test
  void truststore_only_is_one_way_tls() {
    var tls = GrpcClientSslConfig.from(
      config("grpc.client.ssl.truststore.type", "PEM", "grpc.client.ssl.truststore.path", "ca.crt")
    );

    assertThat(tls).isNotNull();
    assertThat(tls.trust()).isInstanceOf(PemTrustOptions.class);
    assertThat(tls.isMutual()).as("no client certificate — not mutual").isFalse();
    assertThat(tls.verifyHostname()).isTrue();
  }

  @Test
  void truststore_plus_keystore_is_mutual_tls() {
    var tls = GrpcClientSslConfig.from(
      config(
        "grpc.client.ssl.truststore.type",
        "PEM",
        "grpc.client.ssl.truststore.path",
        "ca.crt",
        "grpc.client.ssl.keystore.type",
        "PEM",
        "grpc.client.ssl.keystore.path",
        "client.crt",
        "grpc.client.ssl.keystore.keyPath",
        "client.key"
      )
    );

    assertThat(tls).isNotNull();
    assertThat(tls.isMutual()).isTrue();
    assertThat(tls.keyCert()).isInstanceOf(PemKeyCertOptions.class);
  }

  @Test
  void pem_keystore_without_a_key_path_fails_loudly() {
    // Silently dropping the key would degrade mTLS to one-way TLS and only surface
    // as a handshake rejection from the peer.
    assertThatThrownBy(() ->
      GrpcClientSslConfig.from(
        config(
          "grpc.client.ssl.keystore.type",
          "PEM",
          "grpc.client.ssl.keystore.path",
          "client.crt"
        )
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("keyPath");
  }

  @Test
  void unknown_store_type_fails_loudly() {
    assertThatThrownBy(() ->
      GrpcClientSslConfig.from(
        config(
          "grpc.client.ssl.truststore.type",
          "PEM-FOLDER",
          "grpc.client.ssl.truststore.path",
          "certs/"
        )
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("PEM, JKS or PKCS12");
  }

  @Test
  void trust_all_alone_enables_tls_material() {
    var tls = GrpcClientSslConfig.from(config("grpc.client.ssl.trustAll", "true"));

    assertThat(tls).isNotNull();
    assertThat(tls.trustAll()).isTrue();
    assertThat(tls.trust()).isNull();
  }
}
