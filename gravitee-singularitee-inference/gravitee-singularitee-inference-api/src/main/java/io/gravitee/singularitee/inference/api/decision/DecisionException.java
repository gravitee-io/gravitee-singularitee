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
package io.gravitee.singularitee.inference.api.decision;

/**
 * A decision call that cannot be answered. {@link Reason} lets each API surface map it to its
 * own status without parsing the message.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionException extends RuntimeException {

  /** Why the call failed. */
  public enum Reason {
    /** A malformed question or prompt; the message names it ({@code questions.<id>: ...}). */
    INVALID_REQUEST,
    /** An input mode the model's prompt source does not accept (templated or raw). */
    UNSUPPORTED_INPUT,
    /** Media the model cannot read. */
    UNSUPPORTED_MODALITY,
    /** The model ran but produced no usable scores. */
    EVALUATION_FAILED,
  }

  private final Reason reason;

  public DecisionException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public DecisionException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  /** A malformed request about one question. */
  public static DecisionException invalid(String questionId, String message) {
    return new DecisionException(
      Reason.INVALID_REQUEST,
      "questions." + questionId + ": " + message
    );
  }
}
