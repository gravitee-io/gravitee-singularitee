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
package io.gravitee.singularitee.service;

import io.gravitee.singularitee.engine.api.ClientErrors;
import io.gravitee.singularitee.inference.api.ClientFacingException;
import io.reactivex.rxjava3.exceptions.MissingBackpressureException;
import io.vertx.core.Completable;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.server.StatusException;

/**
 * Turns a unary RPC failure into the gRPC status the caller receives.
 *
 * <p>Vert.x sends any failure other than a {@link StatusException} as {@code UNKNOWN} with no
 * message, so the caller could not tell "Model not found" from a crash. The status carries the
 * {@link ClientErrors} text, and a code that says what kind of failure it was.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class GrpcErrors {

  private GrpcErrors() {}

  /** {@code response}, with a failure reported as a {@link StatusException}. */
  static <T> Completable<T> reportingStatus(Completable<T> response) {
    return (result, failure) ->
      response.complete(result, failure == null ? null : toStatus(failure));
  }

  /** The status sent to the caller for {@code error}. */
  static StatusException toStatus(Throwable error) {
    if (error instanceof StatusException status) {
      return status;
    }
    String message = ClientErrors.message(error, "Internal error");
    return new StatusException(statusOf(error), message);
  }

  private static GrpcStatus statusOf(Throwable error) {
    return switch (error) {
      case ClientFacingException caller -> switch (caller.kind()) {
        case NOT_FOUND -> GrpcStatus.NOT_FOUND;
        case INVALID_ARGUMENT, UNSUPPORTED_MODEL -> GrpcStatus.INVALID_ARGUMENT;
      };
      case MissingBackpressureException _ -> GrpcStatus.RESOURCE_EXHAUSTED;
      default -> GrpcStatus.INTERNAL;
    };
  }
}
