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
package io.gravitee.singularitee.http.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.http.resolve.ModelOrPipelineResolver.Resolution;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.InferRequest;
import io.gravitee.singularitee.service.GraviteeInferenceServiceImpl;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DispatchTraceKeyTest {

  private GraviteeInferenceServiceImpl inference;
  private HttpServerRequest request;
  private RoutingContext rc;

  @BeforeEach
  void setUp() {
    inference = mock(GraviteeInferenceServiceImpl.class);
    request = mock(HttpServerRequest.class);
    rc = mock(RoutingContext.class);
    when(rc.request()).thenReturn(request);
    when(rc.response()).thenReturn(mock(HttpServerResponse.class));
  }

  private static Resolution pipeline() {
    return new Resolution(
      true,
      null,
      InferPipelineRequest.newBuilder().setPipelineId("p").setPrompt("hi").build(),
      "pipeline:p",
      false,
      List.of("text")
    );
  }

  private InferPipelineRequest sentPipelineRequest() {
    var captor = ArgumentCaptor.forClass(InferPipelineRequest.class);
    verify(inference).inferPipeline(captor.capture(), any());
    return captor.getValue();
  }

  @Test
  void the_trace_key_header_becomes_the_request_trace_key() {
    when(request.getHeader(Dispatch.TRACE_KEY_HEADER)).thenReturn("  k-1  ");

    Dispatch.drive(inference, pipeline(), rc);

    assertThat(Dispatch.TRACE_KEY_HEADER).isEqualTo("X-Singularitee-Trace-Key");
    assertThat(sentPipelineRequest().getTraceKey()).isEqualTo("k-1");
    assertThat(sentPipelineRequest().getPipelineId()).isEqualTo("p");
  }

  @Test
  void without_the_header_the_request_carries_no_trace_key() {
    Dispatch.drive(inference, pipeline(), rc);

    assertThat(sentPipelineRequest().getTraceKey()).isEmpty();
  }

  @Test
  void a_blank_header_carries_no_trace_key() {
    when(request.getHeader(Dispatch.TRACE_KEY_HEADER)).thenReturn("   ");

    Dispatch.drive(inference, pipeline(), rc);

    assertThat(sentPipelineRequest().getTraceKey()).isEmpty();
  }

  @Test
  void a_plain_model_call_ignores_the_header() {
    when(request.getHeader(Dispatch.TRACE_KEY_HEADER)).thenReturn("k-1");
    var inferRequest = InferRequest.newBuilder().setModelId("llm").setPrompt("hi").build();
    var model = new Resolution(false, inferRequest, null, "llm", false, List.of("text"));

    Dispatch.drive(inference, model, rc);

    verify(inference).infer(any(), any());
    verify(inference, never()).inferPipeline(any(), any());
  }
}
