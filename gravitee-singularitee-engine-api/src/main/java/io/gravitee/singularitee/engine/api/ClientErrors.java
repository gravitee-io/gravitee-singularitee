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
package io.gravitee.singularitee.engine.api;

import io.gravitee.singularitee.inference.api.ClientFacingException;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.reactivex.rxjava3.exceptions.MissingBackpressureException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides what an error tells the caller.
 *
 * <p>Messages the server writes for the caller keep their text: a {@link ClientFacingException}
 * (an unknown id, a model of the wrong kind, a malformed request), a decision the server refused
 * or could not score, a slow-client buffer overflow. Anything else, a plain
 * {@link IllegalArgumentException} from the JDK or a library included, can carry internal paths,
 * hostnames or prompt fragments, so the caller gets a generic message with a reference, and the
 * full error is logged under that reference.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ClientErrors {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClientErrors.class);

  private ClientErrors() {}

  /** Whether {@code error}'s own message is meant for the caller. */
  public static boolean isClientFacing(Throwable error) {
    return (
      error instanceof ClientFacingException ||
      error instanceof DecisionException ||
      error instanceof MissingBackpressureException ||
      error instanceof SanitizedError
    );
  }

  /**
   * The message to send the caller for {@code error}: its own when client-facing, otherwise
   * {@code fallback} plus a reference that the log line carrying the full error repeats.
   */
  public static String message(Throwable error, String fallback) {
    if (error == null) {
      return fallback;
    }
    if (error instanceof SanitizedError sanitized) {
      return sanitized.getMessage();
    }
    if (isClientFacing(error)) {
      return error.getMessage() != null ? error.getMessage() : fallback;
    }
    String ref = UUID.randomUUID().toString().substring(0, 8);
    LOGGER.error("Internal error ref {} returned to the caller as '{}'", ref, fallback, error);
    return fallback + " (ref " + ref + ")";
  }

  /** {@code error} itself when client-facing, otherwise a {@link SanitizedError} for the caller. */
  public static Throwable forClient(Throwable error, String fallback) {
    return isClientFacing(error) ? error : new SanitizedError(message(error, fallback));
  }

  /** A failure whose message has already been made safe for the caller. */
  public static final class SanitizedError extends RuntimeException {

    SanitizedError(String message) {
      super(message, null, false, false);
    }
  }
}
