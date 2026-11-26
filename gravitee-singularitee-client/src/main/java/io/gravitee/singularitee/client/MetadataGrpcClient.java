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

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.Address;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.client.GrpcClientRequest;
import io.vertx.grpc.common.ServiceMethod;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * {@link GrpcClient} decorator that attaches fixed metadata to every outgoing gRPC request:
 * the HTTP Basic {@code authorization} credential, or the W3C trace context of a remote call.
 *
 * <p>gRPC call metadata is carried as HTTP/2 headers, so authenticating against a server
 * protected by {@code GrpcBasicAuthHandler}, or continuing the caller's trace on the server, is a
 * matter of setting a header on each request. Wrapping the {@link GrpcClient} keeps the generated
 * service stubs untouched; they simply receive a request that already carries the metadata.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class MetadataGrpcClient implements GrpcClient {

  private final GrpcClient delegate;
  private final Map<String, String> metadata;

  MetadataGrpcClient(GrpcClient delegate, Map<String, String> metadata) {
    this.delegate = delegate;
    this.metadata = Map.copyOf(metadata);
  }

  /** Sends {@code authorization: Basic base64(username:password)} with every request. */
  static MetadataGrpcClient basicAuth(GrpcClient delegate, String username, String password) {
    String raw = (username == null ? "" : username) + ":" + (password == null ? "" : password);
    return new MetadataGrpcClient(
      delegate,
      Map.of(
        "authorization",
        "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8))
      )
    );
  }

  private <Req, Resp> GrpcClientRequest<Req, Resp> withMetadata(
    GrpcClientRequest<Req, Resp> request
  ) {
    metadata.forEach(request.headers()::set);
    return request;
  }

  @Override
  public Future<GrpcClientRequest<Buffer, Buffer>> request(Address server) {
    return delegate.request(server).map(this::withMetadata);
  }

  @Override
  public Future<GrpcClientRequest<Buffer, Buffer>> request() {
    return delegate.request().map(this::withMetadata);
  }

  @Override
  public <Req, Resp> Future<GrpcClientRequest<Req, Resp>> request(
    Address server,
    ServiceMethod<Resp, Req> method
  ) {
    return delegate.request(server, method).map(this::withMetadata);
  }

  @Override
  public <Req, Resp> Future<GrpcClientRequest<Req, Resp>> request(ServiceMethod<Resp, Req> method) {
    return delegate.request(method).map(this::withMetadata);
  }

  @Override
  public Future<Void> close() {
    return delegate.close();
  }
}
