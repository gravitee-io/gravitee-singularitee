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
package io.gravitee.singularitee.http.translation.wire;

/**
 * Deployment-configurable type strings for the gravitee-namespaced wire events on the
 * Responses API. Renaming is presentation only: the proto contract and internal event
 * identity never change with it. Configured once at boot from
 * {@code http.events.progress-type}; the default is {@code gravitee.progress}.
 */
public final class HttpEventNames {

  public static final String DEFAULT_PROGRESS_TYPE = "gravitee.progress";

  private static volatile String progressType = DEFAULT_PROGRESS_TYPE;

  private HttpEventNames() {}

  /** Applies the deployment's progress event type string (from configuration, at boot). */
  public static void configureProgressType(String type) {
    if (type == null || type.isBlank()) return;
    progressType = type.trim();
  }

  /** The type string PROGRESS events carry on the Responses API. */
  public static String progressType() {
    return progressType;
  }
}
