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
package io.gravitee.singularitee.plugin.route;

import io.gravitee.singularitee.engine.api.pipeline.model.BranchingConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime config of a {@code route} step: the model and strategy that produce a label,
 * and the rules mapping labels to next steps.
 *
 * @param modelId       classifier, embedder or LLM the strategy runs on
 * @param strategy      how the label is produced
 * @param inputField    context key to route on; blank = the prompt
 * @param defaultStepId step taken when no rule matches; blank = terminal
 * @param rules         label-to-step mapping
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record RouteStepConfig(
  String modelId,
  RoutingStrategy strategy,
  String inputField,
  String defaultStepId,
  List<RouteRule> rules
) implements ModelBoundConfig, BranchingConfig {
  public RouteStepConfig {
    modelId = modelId == null ? "" : modelId;
    strategy = strategy == null ? RoutingStrategy.CLASSIFIER : strategy;
    inputField = inputField == null ? "" : inputField;
    defaultStepId = defaultStepId == null ? "" : defaultStepId;
    rules = rules == null ? List.of() : List.copyOf(rules);
  }

  @Override
  public List<String> branchTargets() {
    List<String> targets = new ArrayList<>();
    for (RouteRule rule : rules) {
      if (!rule.nextStepId().isBlank()) targets.add(rule.nextStepId());
    }
    if (!defaultStepId.isBlank()) targets.add(defaultStepId);
    return targets;
  }

  /**
   * One rule: a label, the reference sentences {@link RoutingStrategy#EMBEDDING_KNN}
   * embeds for it (empty = the label text itself) and the step taken when it wins.
   */
  public record RouteRule(String label, List<String> sentences, String nextStepId) {
    public RouteRule {
      label = label == null ? "" : label;
      sentences = sentences == null ? List.of() : List.copyOf(sentences);
      nextStepId = nextStepId == null ? "" : nextStepId;
    }
  }
}
