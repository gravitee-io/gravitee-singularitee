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
package com.graviteesource.resource.singularitee;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.graviteesource.resource.singularitee.configuration.RemoteModelConfiguration;
import io.gravitee.resource.api.ResourceConfiguration;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SingulariteeModelConfiguration(
  String capability,
  RemoteModelConfiguration remote
) implements ResourceConfiguration {
  public SingulariteeModelConfiguration {
    if (
      capability == null ||
      !List.of("token-classification", "text-classification").contains(
        capability
      )
    ) throw new IllegalArgumentException(
      "Unsupported Singularitee model capability"
    );
    if (remote == null) throw new IllegalArgumentException(
      "A deployed Singularitee model is required"
    );
  }
}
