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
package io.gravitee.singularitee.http.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** What {@link RequestModalities} counts as attached media. */
class RequestModalitiesTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void plain_text_carries_no_media() throws Exception {
    assertThat(
      of(
        """
        {"messages":[{"role":"user","content":"hello"}]}
        """
      )
    ).isEmpty();
  }

  @Test
  void text_content_parts_carry_no_media() throws Exception {
    assertThat(
      of(
        """
        {"messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}
        """
      )
    ).isEmpty();
  }

  @Test
  void finds_images_on_both_part_names() throws Exception {
    assertThat(
      of(
        """
        {"messages":[{"role":"user","content":[
          {"type":"image_url","image_url":{"url":"data:image/png;base64,AAA"}}]}]}
        """
      )
    ).containsExactly("image");

    assertThat(
      of(
        """
        {"input":[{"role":"user","content":[
          {"type":"input_image","image_url":"data:image/png;base64,AAA"}]}]}
        """
      )
    ).containsExactly("image");
  }

  @Test
  void finds_audio() throws Exception {
    assertThat(
      of(
        """
        {"messages":[{"role":"user","content":[
          {"type":"input_audio","input_audio":{"data":"AAA","format":"wav"}}]}]}
        """
      )
    ).containsExactly("audio");
  }

  @Test
  void a_tool_schema_mentioning_image_url_is_not_an_attachment() throws Exception {
    // The scan stays inside the conversation: a tool's JSON schema is not media.
    assertThat(
      of(
        """
        {"messages":[{"role":"user","content":"hi"}],
         "tools":[{"type":"function","function":{"name":"f","parameters":{
           "type":"object","properties":{"content":{"type":"image_url"}}}}}]}
        """
      )
    ).isEmpty();
  }

  private static java.util.Set<String> of(String json) throws Exception {
    return RequestModalities.of(MAPPER.readTree(json));
  }
}
