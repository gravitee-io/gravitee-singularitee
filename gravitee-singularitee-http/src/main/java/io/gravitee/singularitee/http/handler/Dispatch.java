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

import io.gravitee.singularitee.http.json.JsonResponses;
import io.gravitee.singularitee.http.resolve.ModelOrPipelineResolver.Resolution;
import io.gravitee.singularitee.http.translation.TokenMessage;
import io.gravitee.singularitee.http.translation.WriteStreamTokenAdapter;
import io.gravitee.singularitee.service.GraviteeInferenceServiceImpl;
import io.reactivex.rxjava3.core.Flowable;
import io.vertx.ext.web.RoutingContext;

/** Drives the local inference service and exposes its token stream to the response formatter. */
public final class Dispatch {

  private Dispatch() {}

  /**
   * Starts generation for the resolved target and returns a hot {@code Flowable<TokenMessage>}.
   *
   * <p>The {@code replay().autoConnect(0)} connects (and starts buffering) before generation begins,
   * so a late formatter subscriber loses no tokens. A client disconnect cancels generation via the
   * adapter's exception hook.
   */
  public static Flowable<TokenMessage> drive(
    GraviteeInferenceServiceImpl inference,
    Resolution res,
    RoutingContext rc
  ) {
    WriteStreamTokenAdapter adapter = new WriteStreamTokenAdapter();
    Flowable<TokenMessage> tokens = adapter
      .asFlowable()
      .takeUntil(TokenMessage::isFinal)
      .replay()
      .autoConnect(0);

    rc.response().closeHandler(v -> adapter.onClientDisconnect());

    if (res.pipeline()) {
      inference.inferPipeline(res.pipelineRequest(), adapter);
    } else {
      inference.infer(res.inferRequest(), adapter);
    }
    return tokens;
  }

  public static void failInternal(RoutingContext rc, Throwable t) {
    String msg = t.getMessage() == null ? "Internal error" : t.getMessage();
    JsonResponses.writeError(rc, 500, msg, "internal_error", null, "internal_error");
  }
}
