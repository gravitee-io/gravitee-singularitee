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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.client.SingulariteeClient;
import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.gravitee.singularitee.protocol.ClassifyLabel;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The remote classifier proxy must put per-request labels on the outgoing
 * {@code Classify} proto request so the remote server can honor them.
 */
class RemoteClassifierEngineTest {

  private static final String MODEL_ID = "sentence-classifier";

  @Test
  void rxClassify_with_labels_puts_them_on_the_outgoing_proto_request() {
    var client = mock(SingulariteeClient.class);
    when(client.classify(any())).thenReturn(
      Single.just(
        io.gravitee.singularitee.protocol.ClassifyResponse.newBuilder()
          .setTopLabel("browse_catalog")
          .setTopScore(0.9f)
          .build()
      )
    );
    var engine = new RemoteClassifierEngine(client, MODEL_ID, null);

    var labels = List.of(
      new ClassifierEngine.ClassifyLabel("browse_catalog", "Browse or search the pet catalog"),
      new ClassifierEngine.ClassifyLabel("place_order", null)
    );
    var response = engine.rxClassify(new ClassifyRequest("show me all pets"), labels).blockingGet();

    assertThat(response.topLabel()).isEqualTo("browse_catalog");

    var captor = ArgumentCaptor.forClass(io.gravitee.singularitee.protocol.ClassifyRequest.class);
    verify(client).classify(captor.capture());
    var protoRequest = captor.getValue();
    assertThat(protoRequest.getModelId()).isEqualTo(MODEL_ID);
    assertThat(protoRequest.getText()).isEqualTo("show me all pets");
    assertThat(protoRequest.getLabelsList())
      .extracting(ClassifyLabel::getName)
      .containsExactly("browse_catalog", "place_order");
    assertThat(protoRequest.getLabelsList().get(0).getDescription()).isEqualTo(
      "Browse or search the pet catalog"
    );
    // Null description must be serialized as the proto default, not NPE.
    assertThat(protoRequest.getLabelsList().get(1).getDescription()).isEmpty();
  }

  @Test
  void rxClassify_without_labels_sends_no_label_override() {
    var client = mock(SingulariteeClient.class);
    when(client.classify(any())).thenReturn(
      Single.just(
        io.gravitee.singularitee.protocol.ClassifyResponse.newBuilder()
          .setTopLabel("toxicity")
          .setTopScore(0.7f)
          .build()
      )
    );
    var engine = new RemoteClassifierEngine(client, MODEL_ID, null);

    var response = engine.rxClassify(new ClassifyRequest("some text")).blockingGet();

    assertThat(response.topLabel()).isEqualTo("toxicity");

    var captor = ArgumentCaptor.forClass(io.gravitee.singularitee.protocol.ClassifyRequest.class);
    verify(client).classify(captor.capture());
    assertThat(captor.getValue().getLabelsList()).isEmpty();
  }
}
