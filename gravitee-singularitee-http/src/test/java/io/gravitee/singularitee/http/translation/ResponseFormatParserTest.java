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
package io.gravitee.singularitee.http.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.http.translation.ResponseFormatParser.InvalidResponseFormatException;
import io.gravitee.singularitee.protocol.StructuredOutputFormat;
import org.junit.jupiter.api.Test;

/** The OpenAI structured-output field, in its Chat Completions and Responses shapes. */
class ResponseFormatParserTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode json(String text) throws Exception {
    return MAPPER.readTree(text);
  }

  @Test
  void chat_reads_the_schema_nested_under_json_schema() throws Exception {
    var payload = json(
      """
      {"response_format":{"type":"json_schema","json_schema":
        {"name":"answer","strict":true,"schema":{"type":"object"}}}}"""
    );

    assertThat(ResponseFormatParser.parse(payload, EndpointType.CHAT)).contains(
      StructuredOutputFormat.newBuilder()
        .setJsonSchema("{\"type\":\"object\"}")
        .setName("answer")
        .build()
    );
  }

  @Test
  void responses_reads_the_schema_flattened_on_text_format() throws Exception {
    var payload = json(
      """
      {"text":{"format":{"type":"json_schema","name":"answer","schema":{"type":"object"}}}}"""
    );

    assertThat(ResponseFormatParser.parse(payload, EndpointType.RESPONSES)).contains(
      StructuredOutputFormat.newBuilder()
        .setJsonSchema("{\"type\":\"object\"}")
        .setName("answer")
        .build()
    );
  }

  @Test
  void json_object_maps_on_both_endpoints() throws Exception {
    var expected = StructuredOutputFormat.newBuilder().setJsonObject(true).build();

    assertThat(
      ResponseFormatParser.parse(
        json("{\"response_format\":{\"type\":\"json_object\"}}"),
        EndpointType.CHAT
      )
    ).contains(expected);
    assertThat(
      ResponseFormatParser.parse(
        json("{\"text\":{\"format\":{\"type\":\"json_object\"}}}"),
        EndpointType.RESPONSES
      )
    ).contains(expected);
  }

  @Test
  void text_and_absent_mean_no_constraint() throws Exception {
    assertThat(
      ResponseFormatParser.parse(
        json("{\"response_format\":{\"type\":\"text\"}}"),
        EndpointType.CHAT
      )
    ).isEmpty();
    assertThat(ResponseFormatParser.parse(json("{}"), EndpointType.CHAT)).isEmpty();
    assertThat(ResponseFormatParser.parse(json("{\"text\":{}}"), EndpointType.RESPONSES)).isEmpty();
  }

  @Test
  void each_endpoint_ignores_the_other_shape() throws Exception {
    var chatShape = json("{\"response_format\":{\"type\":\"json_object\"}}");

    assertThat(ResponseFormatParser.parse(chatShape, EndpointType.RESPONSES)).isEmpty();
    assertThat(ResponseFormatParser.parse(chatShape, EndpointType.COMPLETION)).isEmpty();
  }

  @Test
  void a_malformed_field_names_its_param() throws Exception {
    assertThatThrownBy(() ->
      ResponseFormatParser.parse(
        json("{\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{\"schema\":{}}}}"),
        EndpointType.CHAT
      )
    )
      .isInstanceOfSatisfying(InvalidResponseFormatException.class, e ->
        assertThat(e.param()).isEqualTo("response_format.json_schema")
      )
      .hasMessageContaining("name");

    assertThatThrownBy(() ->
      ResponseFormatParser.parse(
        json("{\"text\":{\"format\":{\"type\":\"json_schema\",\"name\":\"a\"}}}"),
        EndpointType.RESPONSES
      )
    )
      .isInstanceOfSatisfying(InvalidResponseFormatException.class, e ->
        assertThat(e.param()).isEqualTo("text.format")
      )
      .hasMessageContaining("schema");

    assertThatThrownBy(() ->
      ResponseFormatParser.parse(
        json("{\"response_format\":{\"type\":\"xml\"}}"),
        EndpointType.CHAT
      )
    ).isInstanceOf(InvalidResponseFormatException.class);
  }
}
