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

import com.google.protobuf.Value;
import io.gravitee.singularitee.engine.api.decision.DecisionMessages;
import io.gravitee.singularitee.inference.api.decision.DecisionCapabilities;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.protocol.DecisionInfo;
import io.vertx.core.json.Json;
import io.vertx.grpc.common.GrpcStatus;

/**
 * The server-side parts of decision messages that the engine contract does not need: discovery,
 * gRPC status codes, and the JSON text of a state handed to a pipeline. The request and answer
 * translations are {@link DecisionMessages}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionProtoMapper {

  private DecisionProtoMapper() {}

  /** The discovery block of a decision model. */
  public static DecisionInfo info(DecisionCapabilities capabilities) {
    return DecisionInfo.newBuilder()
      .setFamily(capabilities.family().wireName())
      .setMaxOptions(capabilities.maxOptions())
      .setJoint(capabilities.joint())
      .setTemplated(capabilities.templated())
      .setRaw(capabilities.raw())
      .build();
  }

  /** The gRPC status of a decision failure. */
  public static GrpcStatus status(Reason reason) {
    return switch (reason) {
      case INVALID_REQUEST -> GrpcStatus.INVALID_ARGUMENT;
      case UNSUPPORTED_INPUT, UNSUPPORTED_MODALITY -> GrpcStatus.FAILED_PRECONDITION;
      case EVALUATION_FAILED -> GrpcStatus.INTERNAL;
    };
  }

  /** A protobuf {@link Value} as compact JSON text. */
  public static String toJson(Value value) {
    return Json.encode(DecisionMessages.toJava(value));
  }
}
