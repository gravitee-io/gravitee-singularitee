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
package io.gravitee.singularitee.engine.api.pipeline.model;

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import java.util.List;

/**
 * A step config that brings its own classifier labels. Non-empty labels replace the model's on
 * every call the step makes, so the registry refuses the pipeline at registration unless the
 * step's model takes request labels ({@link ClassifierEngine#acceptsRequestLabels()}).
 *
 * @author GraviteeSource Team
 */
public interface ClassifierLabelsConfig extends ModelBoundConfig {
  /** The labels the step classifies against; empty means the model's own. */
  List<ClassifierEngine.ClassifyLabel> labels();
}
