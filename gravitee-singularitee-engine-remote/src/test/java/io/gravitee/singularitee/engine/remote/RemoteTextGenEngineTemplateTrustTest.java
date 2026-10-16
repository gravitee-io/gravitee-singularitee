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
package io.gravitee.singularitee.engine.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.client.SingulariteeClient;
import io.gravitee.singularitee.protocol.GetModelResponse;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A chat template sent by the remote is Jinja the client runs; it needs an authenticated channel. */
class RemoteTextGenEngineTemplateTrustTest {

  private static final String TEMPLATE = "{% for m in messages %}{{ m.content }}{% endfor %}";

  private static SingulariteeClient client(boolean secure) {
    var client = mock(SingulariteeClient.class);
    when(client.isSecureChannel()).thenReturn(secure);
    when(client.getModel("llm")).thenReturn(
      Single.just(
        GetModelResponse.newBuilder()
          .setChatTemplate(TEMPLATE)
          .setBosToken("<s>")
          .addInputModalities("text")
          .addInputModalities("image")
          .build()
      )
    );
    return client;
  }

  @Test
  void a_template_fetched_over_a_secure_channel_is_used() {
    var engine = new RemoteTextGenEngine(client(true), "llm");

    assertThat(engine.chatTemplateString()).isEqualTo(TEMPLATE);
    assertThat(engine.bosToken()).isEqualTo("<s>");
  }

  @Test
  void a_template_fetched_over_plaintext_is_ignored_but_modalities_are_kept() {
    var engine = new RemoteTextGenEngine(client(false), "llm");

    assertThat(engine.chatTemplateString()).isNull();
    assertThat(engine.bosToken()).isEmpty();
    assertThat(engine.inputModalities()).containsExactly("text", "image");
  }

  @Test
  void a_supplied_template_from_an_insecure_remote_is_ignored() {
    var engine = new RemoteTextGenEngine(client(false), "llm", TEMPLATE, "<s>", "</s>", List.of());

    assertThat(engine.chatTemplateString()).isNull();
  }

  @Test
  void a_loopback_or_verified_tls_client_is_a_secure_channel() {
    try (var loopback = new SingulariteeClient("127.0.0.1", 1)) {
      assertThat(loopback.isSecureChannel()).isTrue();
    }
    try (var remote = new SingulariteeClient("models.internal", 1)) {
      assertThat(remote.isSecureChannel()).isFalse();
    }
  }
}
