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
package com.graviteesource.resource.singularitee.client;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.graviteesource.resource.singularitee.SingulariteeModelConfiguration;
import com.graviteesource.resource.singularitee.configuration.RemoteModelConfiguration;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SingulariteeClassificationClientTest {

  @Test
  void maps_text_classification_scores_without_weakening_token_contract() {
    var body =
      "{\"model\":\"pii\",\"results\":[{\"scores\":{\"sensitive\":0.95}}]}".getBytes(
        StandardCharsets.UTF_8
      );
    assertThat(
      SingulariteeClassificationClient.parse(
        body,
        "pii",
        "text-classification"
      ).results()
    )
      .singleElement()
      .satisfies(label -> {
        assertThat(label.label()).isEqualTo("sensitive");
        assertThat(label.score()).isEqualTo(0.95f);
      });
    assertThatThrownBy(() ->
      SingulariteeClassificationClient.parse(
        "{\"model\":\"pii\",\"results\":[{\"scores\":{}}]}".getBytes(
          StandardCharsets.UTF_8
        ),
        "pii",
        "text-classification"
      )
    ).hasMessageContaining("Invalid");
    assertThatThrownBy(() ->
      SingulariteeClassificationClient.parse(
        body,
        "pii",
        "token-classification"
      )
    ).hasMessageContaining("Invalid");
    assertThatThrownBy(() ->
      SingulariteeClassificationClient.parse(
        new String(body, StandardCharsets.UTF_8)
          .replace("0.95", "2")
          .getBytes(StandardCharsets.UTF_8),
        "pii",
        "text-classification"
      )
    ).hasMessageContaining("Invalid");
  }

  @Test
  void maps_entities_and_discards_outside_tokens() {
    var result = parse(
      """
      {"model":"pii","results":[{"scores":{},"spans":[
        {"label":"O","score":0.9,"start":0,"end":4},
        {"label":"B-EMAIL_ADDRESS","score":0.97,"token":"demo@example.com","start":5,"end":21}
      ]}]}
      """
    );
    assertThat(result.results())
      .singleElement()
      .satisfies(entity -> {
        assertThat(entity.label()).isEqualTo("B-EMAIL_ADDRESS");
        assertThat(entity.score()).isEqualTo(0.97f);
        assertThat(entity.start()).isEqualTo(5);
      });
  }

  @Test
  void accepts_explicit_empty_result_but_rejects_missing_results_and_bad_spans() {
    assertThat(
      parse("{\"model\":\"pii\",\"results\":[{\"scores\":{}}]}").results()
    ).isEmpty();
    for (var body : new String[] {
      "{}",
      "{\"model\":\"pii\",\"results\":[{\"scores\":{\"EMAIL\":0.9},\"spans\":[]}]}",
      "{\"model\":\"pii\",\"results\":[]}",
      "{\"model\":\"other\",\"results\":[{\"scores\":{}}]}",
      "{\"model\":\"pii\",\"results\":[{\"scores\":{\"EMAIL\":0.9}}]}",
      "{\"model\":\"pii\",\"results\":[{\"scores\":{},\"spans\":[{\"label\":\"EMAIL\",\"score\":2,\"start\":0,\"end\":1}]}]}",
    })
      assertThatThrownBy(() -> parse(body)).hasMessage(
        "Invalid classification response"
      );
  }

  @Test
  void errors_for_missing_auth_and_timeout() {
    var server = new WireMockServer(0);
    server.start();
    var vertx = io.vertx.rxjava3.core.Vertx.vertx();
    try (
      var client = new SingulariteeClassificationClient(
        vertx,
        config(server.baseUrl(), "credential-reference", 100L)
      )
    ) {
      assertThatThrownBy(() ->
        client
          .classify(new PromptInput("hello"), Map.of(), "token-classification")
          .blockingGet()
      ).hasMessageContaining("authorization");
      server.stubFor(
        post("/v1/classify").willReturn(okJson("{}").withFixedDelay(500))
      );
      assertThatThrownBy(() ->
        client
          .classify(
            new PromptInput("hello"),
            Map.of("Authorization", "Bearer test-token"),
            "token-classification"
          )
          .blockingGet()
      );
    } finally {
      server.stop();
      vertx.close().blockingAwait();
    }
  }

  @Test
  void validates_the_selected_capability_and_serving_binding() {
    var remote = config("http://localhost:8080", "secret-value", null);
    assertThat(
      new SingulariteeModelConfiguration(
        "token-classification",
        remote
      ).remote()
    ).isEqualTo(remote);
    assertThat(
      new SingulariteeModelConfiguration("text-classification", remote).remote()
    ).isEqualTo(remote);
    assertThatThrownBy(() ->
      new SingulariteeModelConfiguration("embedding", remote)
    ).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
      new SingulariteeModelConfiguration("token-classification", null)
    ).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> config(null, null, null)).hasMessage(
      "A valid HTTP serving endpoint is required"
    );
    assertThat(remote.toString()).doesNotContain("secret-value");
  }

  @Test
  void rejects_oversized_bodies_and_keeps_invalid_credentials_out_of_errors() {
    var server = new WireMockServer(0);
    server.start();
    var vertx = io.vertx.rxjava3.core.Vertx.vertx();
    try (
      var client = new SingulariteeClassificationClient(
        vertx,
        config(server.baseUrl(), "credential-reference", 5000L)
      )
    ) {
      assertThatThrownBy(() ->
        client
          .classify(
            new PromptInput("hello"),
            Map.of("Authorization", "Bearer private\nvalue"),
            "token-classification"
          )
          .blockingGet()
      )
        .hasMessage("Classification request could not be sent")
        .hasNoCause();
      server.verify(0, postRequestedFor(urlEqualTo("/v1/classify")));
      server.stubFor(
        post("/v1/classify").willReturn(okJson(" ".repeat(1024 * 1024 + 1)))
      );
      assertThatThrownBy(() ->
        client
          .classify(
            new PromptInput("hello"),
            Map.of("Authorization", "Bearer test-token"),
            "token-classification"
          )
          .blockingGet()
      )
        .hasMessage("Classification service unavailable")
        .hasNoCause();
    } finally {
      server.stop();
      vertx.close().blockingAwait();
    }
  }

  private static RemoteModelConfiguration config(
    String endpoint,
    String auth,
    Long timeout
  ) {
    return new RemoteModelConfiguration(
      "singularitee",
      endpoint,
      "pii",
      auth,
      timeout
    );
  }

  private static io.gravitee.resource.ai_model.api.result.ClassifierResults parse(
    String json
  ) {
    return SingulariteeClassificationClient.parse(
      json.getBytes(StandardCharsets.UTF_8),
      "pii",
      "token-classification"
    );
  }
}
