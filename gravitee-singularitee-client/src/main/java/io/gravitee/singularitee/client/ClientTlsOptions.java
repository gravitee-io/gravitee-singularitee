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
package io.gravitee.singularitee.client;

import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.TrustOptions;

/**
 * Outbound TLS material for {@link SingulariteeClient}.
 *
 * <p>Deliberately expressed in plain Vert.x types: the caller decides where the
 * certificates come from (a {@code gravitee.yml} block, a vault, a test fixture) and this
 * module stays free of configuration dependencies — it is the one artifact a gateway
 * connector embeds.
 *
 * <p>Everything is optional. With no trust material the JVM default trust store applies,
 * which is enough for a publicly-trusted certificate; a private CA needs {@code trust}.
 * {@code keyCert} is what makes the connection <em>mutual</em> — without it the client
 * cannot answer a server running {@code grpc.ssl.clientAuth: REQUIRED}.
 *
 * @param trust          material verifying the server, or {@code null} for the JVM default trust store
 * @param keyCert        this client's own certificate and key, or {@code null} for one-way TLS
 * @param trustAll       accept any server certificate — development only, defeats the point of TLS
 * @param verifyHostname whether the certificate must match the host being dialled
 */
public record ClientTlsOptions(
  TrustOptions trust,
  KeyCertOptions keyCert,
  boolean trustAll,
  boolean verifyHostname
) {
  /** Verifies the server against the given trust material, presenting no client certificate. */
  public static ClientTlsOptions trusting(TrustOptions trust) {
    return new ClientTlsOptions(trust, null, false, true);
  }

  /** Mutual TLS: verify the server against {@code trust} and present {@code keyCert}. */
  public static ClientTlsOptions mutual(TrustOptions trust, KeyCertOptions keyCert) {
    return new ClientTlsOptions(trust, keyCert, false, true);
  }

  /** Whether a client certificate is configured, i.e. whether this is mutual TLS. */
  public boolean isMutual() {
    return keyCert != null;
  }
}
