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

import io.gravitee.singularitee.inference.api.ClientFacingException;

/**
 * A decision call whose target cannot serve it: an unknown id, or a model or pipeline that does
 * not decide. Its message is written for the caller.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionTargetException extends ClientFacingException {

  private final boolean notFound;

  private DecisionTargetException(String message, boolean notFound) {
    super(notFound ? Kind.NOT_FOUND : Kind.UNSUPPORTED_MODEL, message);
    this.notFound = notFound;
  }

  /** The id names no published model or pipeline. */
  public static DecisionTargetException notFound(String id) {
    return new DecisionTargetException("Model not found: " + id, true);
  }

  /** The id names something that does not decide. */
  public static DecisionTargetException unsupported(String id, String why) {
    return new DecisionTargetException("'" + id + "' " + why, false);
  }

  /** Whether the id is unknown, rather than known but unable to decide. */
  public boolean notFound() {
    return notFound;
  }
}
