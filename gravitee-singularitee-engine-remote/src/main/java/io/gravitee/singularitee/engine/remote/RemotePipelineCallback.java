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

import io.gravitee.singularitee.client.SingulariteeClient;
import io.gravitee.singularitee.pipeline.executor.SubPipelineStepExecutor;
import io.gravitee.singularitee.protocol.*;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Context;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SubPipelineStepExecutor.PipelineExecutorCallback} that executes
 * a pipeline on a remote Singularitee via the {@code InferPipeline} RPC.
 *
 * <p>Fully non-blocking: returns a {@link Completable} that completes when the
 * remote stream finishes (COMPLETED or FAILED event received), without blocking
 * any thread.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RemotePipelineCallback
  implements SubPipelineStepExecutor.PipelineExecutorCallback {

  private static final Logger LOGGER = LoggerFactory.getLogger(RemotePipelineCallback.class);

  private final SingulariteeClient client;

  public RemotePipelineCallback(SingulariteeClient client) {
    this.client = client;
  }

  @Override
  public Completable executePipeline(
    InferPipelineRequest request,
    WriteStream<InferResponse> response,
    Context callerContext
  ) {
    LOGGER.info(
      "Executing remote sub-pipeline '{}' on thread '{}'",
      request.getPipelineId(),
      Thread.currentThread().getName()
    );

    var ended = new java.util.concurrent.atomic.AtomicBoolean(false);

    return client
      .inferPipeline(request)
      .doOnNext(event -> {
        var eventType = event.getEventType();
        if (
          eventType == ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED ||
          eventType == ResponseEventType.RESPONSE_EVENT_TYPE_FAILED
        ) {
          ended.set(true);
          response.end(event);
        } else {
          response.write(event);
        }
      })
      .doOnError(err -> {
        LOGGER.error(
          "Remote sub-pipeline '{}' failed: {}",
          request.getPipelineId(),
          err.getMessage(),
          err
        );
        // Ensure the response stream is closed even if no terminal event was received
        if (ended.compareAndSet(false, true)) {
          response.end(
            InferResponse.newBuilder()
              .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_FAILED)
              .setResponseFailed(
                ResponseFailed.newBuilder()
                  .setErrorCode("server_error")
                  .setErrorMessage(
                    err.getMessage() != null ? err.getMessage() : "Remote pipeline failed"
                  )
                  .build()
              )
              .build()
          );
        }
      })
      .doOnComplete(() ->
        LOGGER.info("Remote sub-pipeline '{}' stream completed", request.getPipelineId())
      )
      .onErrorComplete() // Error logged + stream closed in doOnError
      .ignoreElements(); // Flowable → Completable
  }
}
