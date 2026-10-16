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
package io.gravitee.singularitee.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.metrics.InferenceMetrics;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.InferRequest;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.MediaContent;
import io.gravitee.singularitee.protocol.MediaType;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.gravitee.singularitee.protocol.Role;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.streams.WriteStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A raw {@code Infer} refuses media its model cannot read before anything reaches the engine. */
class GraviteeInferenceServiceImplModalityTest {

  private static final String MODEL_ID = "llm";

  private static Vertx vertx;

  private TextGenEngine engine;
  private GraviteeInferenceServiceImpl service;

  @BeforeAll
  static void startVertx() {
    vertx = Vertx.vertx();
  }

  @AfterAll
  static void stopVertx() {
    vertx.close();
  }

  @BeforeEach
  void setUp() {
    var registry = new ModelRegistry();
    engine = mock(TextGenEngine.class);
    registry.register(MODEL_ID, "test-llm", engine, token -> {});
    service = new GraviteeInferenceServiceImpl(
      vertx,
      registry,
      null,
      mock(Tracer.class),
      new InferenceMetrics(null),
      false
    );
  }

  @Test
  void image_for_a_text_only_model_is_refused() {
    when(engine.inputModalities()).thenReturn(List.of("text"));
    var response = new RecordingStream();

    service.infer(withMedia(MediaType.MEDIA_TYPE_IMAGE_PNG), response);

    assertRefused(response, "image");
  }

  @Test
  void audio_for_a_vision_model_is_refused() {
    when(engine.inputModalities()).thenReturn(List.of("text", "image"));
    var response = new RecordingStream();

    service.infer(withMedia(MediaType.MEDIA_TYPE_AUDIO_WAV), response);

    assertRefused(response, "audio");
  }

  private static InferRequest withMedia(MediaType type) {
    return InferRequest.newBuilder()
      .setModelId(MODEL_ID)
      .setMessages(
        ChatMessageList.newBuilder().addMessages(
          ChatMessage.newBuilder()
            .setRole(Role.ROLE_USER)
            .setContent("what is this?")
            .addMedia(
              MediaContent.newBuilder().setMediaType(type).setData(ByteString.copyFromUtf8("AAAA"))
            )
        )
      )
      .build();
  }

  private void assertRefused(RecordingStream response, String modality) {
    assertThat(response.ended).isTrue();
    assertThat(response.items).hasSize(1);
    var failed = response.items.getFirst();
    assertThat(failed.getEventType()).isEqualTo(ResponseEventType.RESPONSE_EVENT_TYPE_FAILED);
    assertThat(failed.getResponseFailed().getErrorCode()).isEqualTo("unsupported_modality");
    assertThat(failed.getResponseFailed().getErrorMessage()).contains(modality);
    verify(engine, never()).rxAddSequence(anyInt(), any());
  }

  private static final class RecordingStream implements WriteStream<InferResponse> {

    final List<InferResponse> items = new ArrayList<>();
    boolean ended;

    @Override
    public WriteStream<InferResponse> exceptionHandler(Handler<Throwable> handler) {
      return this;
    }

    @Override
    public Future<Void> write(InferResponse data) {
      items.add(data);
      return Future.succeededFuture();
    }

    @Override
    public Future<Void> end() {
      ended = true;
      return Future.succeededFuture();
    }

    @Override
    public WriteStream<InferResponse> setWriteQueueMaxSize(int maxSize) {
      return this;
    }

    @Override
    public boolean writeQueueFull() {
      return false;
    }

    @Override
    public WriteStream<InferResponse> drainHandler(Handler<Void> handler) {
      return this;
    }
  }
}
