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
package io.gravitee.singularitee.engine.api.pipeline.executor;

/**
 * Deployment-wide tracing toggles, set once at startup: whether to emit OpenInference semantic
 * attributes, and whether spans carry content-heavy attributes (message text, input/output
 * values). Both default to safe values and are read at span-write time.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class TracingOptions {

  private static volatile boolean openInference = true;
  private static volatile boolean verbose = false;

  private TracingOptions() {}

  /** Sets the toggles; called once from the container at startup. */
  public static void configure(boolean openInferenceEnabled, boolean verboseEnabled) {
    openInference = openInferenceEnabled;
    verbose = verboseEnabled;
  }

  /** Whether OpenInference semantic-convention attributes are emitted (default true). */
  public static boolean openInference() {
    return openInference;
  }

  /** Whether content-heavy attributes (message text, input/output values) are emitted. */
  public static boolean verbose() {
    return verbose;
  }
}
