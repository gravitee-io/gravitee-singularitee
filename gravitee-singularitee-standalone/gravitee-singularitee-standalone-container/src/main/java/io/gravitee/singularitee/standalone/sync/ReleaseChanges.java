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
package io.gravitee.singularitee.standalone.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tells whether a release can be applied to the running node without a restart: it may differ
 * from the running release only in {@code workspace.pipelines} and {@code workspace.templates}.
 *
 * <p>Both releases are compared as trees, so key order and formatting do not count. Anything
 * else counts as a change that needs a restart: a release that does not parse, a running
 * release the node does not know, {@code includes} (they name files a string cannot resolve),
 * and a changed template a model reads (a decision model's {@code prompt.template} is
 * materialised when the model loads).
 */
final class ReleaseChanges {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final String WORKSPACE = "workspace";
  private static final String PIPELINES = "pipelines";
  private static final String TEMPLATES = "templates";

  private ReleaseChanges() {}

  static boolean pipelinesOrTemplatesOnly(String running, String candidate) {
    if (running == null || candidate == null) {
      return false;
    }
    JsonNode before;
    JsonNode after;
    try {
      before = YAML.readTree(running);
      after = YAML.readTree(candidate);
    } catch (IOException e) {
      return false;
    }
    if (
      !(before instanceof ObjectNode beforeRoot) ||
      !(after instanceof ObjectNode afterRoot) ||
      !(beforeRoot.get(WORKSPACE) instanceof ObjectNode beforeWorkspace) ||
      !(afterRoot.get(WORKSPACE) instanceof ObjectNode afterWorkspace)
    ) {
      return false;
    }
    if (beforeWorkspace.has("includes") || afterWorkspace.has("includes")) {
      return false;
    }
    if (changedTemplateReadByModels(beforeWorkspace, afterWorkspace)) {
      return false;
    }
    return withoutPipelinesAndTemplates(beforeRoot).equals(withoutPipelinesAndTemplates(afterRoot));
  }

  private static ObjectNode withoutPipelinesAndTemplates(ObjectNode root) {
    ObjectNode copy = root.deepCopy();
    ((ObjectNode) copy.get(WORKSPACE)).remove(Set.of(PIPELINES, TEMPLATES));
    return copy;
  }

  private static boolean changedTemplateReadByModels(JsonNode before, JsonNode after) {
    Map<String, JsonNode> old = templatesById(before);
    Map<String, JsonNode> current = templatesById(after);
    Set<String> ids = new HashSet<>(old.keySet());
    ids.addAll(current.keySet());
    return ids
      .stream()
      .filter(id -> !Objects.equals(old.get(id), current.get(id)))
      .anyMatch(id -> mentions(before.path("models"), id) || mentions(after.path("models"), id));
  }

  private static Map<String, JsonNode> templatesById(JsonNode workspace) {
    Map<String, JsonNode> byId = new HashMap<>();
    for (JsonNode template : workspace.path(TEMPLATES)) {
      byId.put(template.path("id").asText(""), template);
    }
    return byId;
  }

  private static boolean mentions(JsonNode node, String text) {
    if (node.isTextual()) {
      return node.asText().equals(text);
    }
    for (JsonNode child : node) {
      if (mentions(child, text)) {
        return true;
      }
    }
    return false;
  }
}
