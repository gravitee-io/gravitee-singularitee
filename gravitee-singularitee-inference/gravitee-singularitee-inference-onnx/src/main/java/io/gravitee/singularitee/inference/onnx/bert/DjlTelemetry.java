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
package io.gravitee.singularitee.inference.onnx.bert;

/**
 * Opts out of DJL telemetry before the first tokenizer is loaded.
 *
 * <p>When it loads its native tokenizer library, DJL calls {@code Ec2Utils.callHome}, which
 * queries the EC2 instance metadata service (169.254.169.254) and then a DJL telemetry bucket on
 * S3. Where the metadata service is blocked, each request waits for its timeout on the thread that
 * builds the model. DJL skips the call when {@code OPT_OUT_TRACKING} is {@code true}, read from
 * the environment first, then from system properties.
 *
 * <p>A value the operator has already set, as an environment variable or a system property, is
 * left as is.
 */
public final class DjlTelemetry {

  static final String OPT_OUT_TRACKING = "OPT_OUT_TRACKING";

  private DjlTelemetry() {}

  public static void optOut() {
    if (System.getenv(OPT_OUT_TRACKING) == null && System.getProperty(OPT_OUT_TRACKING) == null) {
      System.setProperty(OPT_OUT_TRACKING, Boolean.TRUE.toString());
    }
  }
}
