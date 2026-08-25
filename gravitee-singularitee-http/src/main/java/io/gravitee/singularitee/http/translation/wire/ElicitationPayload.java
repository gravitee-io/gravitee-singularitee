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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.gravitee.singularitee.protocol.Elicitation;
import java.util.List;
import java.util.Map;

/**
 * The structured elicitation payload riding a progress event when a step pauses to ask the
 * user something: the {@code kind} names the elicitation category (the core {@code ask_user} tool emits
 * {@code ask}), {@code options} enumerate closed answers, and {@code metadata} carries any
 * free-form entries a step plugin attached.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ElicitationPayload(
  String kind,
  String question,
  List<String> options,
  Map<String, String> metadata
) {
  public ElicitationPayload(Elicitation e) {
    this(e.getKind(), e.getQuestion(), e.getOptionsList(), e.getMetadataMap());
  }
}
