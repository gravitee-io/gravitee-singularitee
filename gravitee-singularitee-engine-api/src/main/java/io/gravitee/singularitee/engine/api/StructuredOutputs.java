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
package io.gravitee.singularitee.engine.api;

import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.protocol.ChoiceList;
import io.gravitee.singularitee.protocol.Grammar;
import io.gravitee.singularitee.protocol.StructuredOutputFormat;

/**
 * Maps the wire {@link StructuredOutputFormat} to and from the engine {@link StructuredOutput}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class StructuredOutputs {

  private StructuredOutputs() {}

  /**
   * @return the constraint, or {@code null} when {@code wire} is null or names no format
   * @throws IllegalArgumentException when the format is present but empty
   */
  public static StructuredOutput fromProto(StructuredOutputFormat wire) {
    if (wire == null) {
      return null;
    }
    return switch (wire.getKindCase()) {
      case JSON_SCHEMA -> new StructuredOutput.JsonSchema(wire.getJsonSchema());
      // Any set `json_object` is the constraint: the oneof already carries the intent, and a
      // client that sent `false` meant to constrain, not to be silently unconstrained.
      case JSON_OBJECT -> new StructuredOutput.JsonObject();
      case CHOICE -> new StructuredOutput.Choice(wire.getChoice().getValuesList());
      case REGEX -> new StructuredOutput.Regex(wire.getRegex());
      case GRAMMAR -> new StructuredOutput.Grammar(
        wire.getGrammar().getText(),
        wire.getGrammar().getRoot()
      );
      case KIND_NOT_SET -> null;
    };
  }

  /** @return the wire form, or {@code null} when {@code format} is null */
  public static StructuredOutputFormat toProto(StructuredOutput format) {
    if (format == null) {
      return null;
    }
    var wire = StructuredOutputFormat.newBuilder();
    switch (format) {
      case StructuredOutput.JsonSchema(String schema) -> wire.setJsonSchema(schema);
      case StructuredOutput.JsonObject() -> wire.setJsonObject(true);
      case StructuredOutput.Choice(var values) -> wire.setChoice(
        ChoiceList.newBuilder().addAllValues(values)
      );
      case StructuredOutput.Regex(String pattern) -> wire.setRegex(pattern);
      case StructuredOutput.Grammar(String text, String root) -> wire.setGrammar(
        Grammar.newBuilder().setText(text).setRoot(root)
      );
    }
    return wire.build();
  }
}
