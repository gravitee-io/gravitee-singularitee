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
package io.gravitee.singularitee.inference.api;

/**
 * A failure whose message is written for the caller, with the kind of failure it is.
 *
 * <p>Only this type (and the few named in {@code ClientErrors}) reaches a caller verbatim; any
 * other exception, a plain {@link IllegalArgumentException} from the JDK or a library included,
 * is reported as a generic internal error. The kind, not the message text, picks the gRPC status
 * and the HTTP error code. It is an {@link IllegalArgumentException} so request parsing that
 * catches one keeps treating it as a bad request.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class ClientFacingException extends IllegalArgumentException {

  /** What kind of caller error this is. */
  public enum Kind {
    /** The request is malformed or asks for something the target cannot do. */
    INVALID_ARGUMENT,
    /** The model or pipeline the request names does not exist. */
    NOT_FOUND,
    /** The named model exists but is not of the kind the call needs. */
    UNSUPPORTED_MODEL,
  }

  private final Kind kind;

  public ClientFacingException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  /** A malformed request. */
  public static ClientFacingException invalid(String message) {
    return new ClientFacingException(Kind.INVALID_ARGUMENT, message);
  }

  /** {@code Model not found: <id>}. */
  public static ClientFacingException modelNotFound(String modelId) {
    return new ClientFacingException(Kind.NOT_FOUND, "Model not found: " + modelId);
  }

  /** {@code Pipeline not found: <id>}. */
  public static ClientFacingException pipelineNotFound(String pipelineId) {
    return new ClientFacingException(Kind.NOT_FOUND, "Pipeline not found: " + pipelineId);
  }

  /** A model of the wrong kind for the call. */
  public static ClientFacingException unsupportedModel(String message) {
    return new ClientFacingException(Kind.UNSUPPORTED_MODEL, message);
  }

  public Kind kind() {
    return kind;
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    // A caller error is control flow, not a fault: no stack to capture.
    return this;
  }
}
