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

/**
 * {@link GrpcClient} decorator that attaches an HTTP Basic {@code authorization}
 * header to every outgoing gRPC request.
 *
 * <p>gRPC call metadata is carried as HTTP/2 headers, so authenticating against a
 * server protected by {@code GrpcBasicAuthHandler} is a matter of setting the
 * {@code authorization: Basic base64(username:password)} header on each request.
 * Wrapping the {@link GrpcClient} keeps the generated service stubs untouched —
 * they simply receive a request that already carries the credential.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class BasicAuthGrpcClient implements GrpcClient {

  private final GrpcClient delegate;
  private final String authorizationHeader;

  BasicAuthGrpcClient(GrpcClient delegate, String username, String password) {
    this.delegate = delegate;
    String raw = (username == null ? "" : username) + ":" + (password == null ? "" : password);
    this.authorizationHeader =
      "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private <Req, Resp> GrpcClientRequest<Req, Resp> withAuth(GrpcClientRequest<Req, Resp> request) {
    request.headers().set("authorization", authorizationHeader);
    return request;
  }

  @Override
  public Future<GrpcClientRequest<Buffer, Buffer>> request(Address server) {
    return delegate.request(server).map(this::withAuth);
  }

  @Override
  public Future<GrpcClientRequest<Buffer, Buffer>> request() {
    return delegate.request().map(this::withAuth);
  }

  @Override
  public <Req, Resp> Future<GrpcClientRequest<Req, Resp>> request(
    Address server,
    ServiceMethod<Resp, Req> method
  ) {
    return delegate.request(server, method).map(this::withAuth);
  }

  @Override
  public <Req, Resp> Future<GrpcClientRequest<Req, Resp>> request(ServiceMethod<Resp, Req> method) {
    return delegate.request(method).map(this::withAuth);
  }

  @Override
  public Future<Void> close() {
    return delegate.close();
  }
}
