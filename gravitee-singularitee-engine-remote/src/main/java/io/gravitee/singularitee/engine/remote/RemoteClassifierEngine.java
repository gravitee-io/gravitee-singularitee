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
import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifyResult;
import io.gravitee.singularitee.engine.ModelTasks;
import io.reactivex.rxjava3.core.Single;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remote proxy for {@link ClassifierEngine} that calls the server's {@code Classify} RPC.
 *
 * <p>Fully non-blocking: delegates directly to the gRPC {@link Single} returned by the client
 * — no {@code blockingGet()} required.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RemoteClassifierEngine implements ClassifierEngine {

  private final SingulariteeClient client;
  private final String modelId;
  private final String task;

  /**
   * Creates a remote classifier engine with the given task metadata.
   *
   * @param client  the shared Singularitee gRPC client
   * @param modelId the remote model identifier
   * @param task    the classifier task slug ({@link ModelTasks#TEXT_CLASSIFICATION} or
   *                {@link ModelTasks#TOKEN_CLASSIFICATION}); when {@code null} or blank,
   *                defaults to {@link ModelTasks#TEXT_CLASSIFICATION}
   */
  public RemoteClassifierEngine(SingulariteeClient client, String modelId, String task) {
    this.client = client;
    this.modelId = modelId;
    this.task = (task == null || task.isBlank()) ? ModelTasks.TEXT_CLASSIFICATION : task;
  }

  /**
   * Backwards-compatible constructor that assumes sequence-level classification.
   *
   * @deprecated use {@link #RemoteClassifierEngine(SingulariteeClient, String, String)}
   * to propagate the remote model's task metadata.
   */
  @Deprecated
  public RemoteClassifierEngine(SingulariteeClient client, String modelId) {
    this(client, modelId, ModelTasks.TEXT_CLASSIFICATION);
  }

  @Override
  public String task() {
    return task;
  }

  @Override
  public Single<io.gravitee.singularitee.engine.ClassifyResponse> rxClassify(
    io.gravitee.singularitee.engine.ClassifyRequest request
  ) {
    return rxClassify(request, List.of());
  }

  @Override
  public Single<io.gravitee.singularitee.engine.ClassifyResponse> rxClassify(
    io.gravitee.singularitee.engine.ClassifyRequest request,
    List<ClassifyLabel> labels
  ) {
    var builder = io.gravitee.singularitee.protocol.ClassifyRequest.newBuilder()
      .setModelId(modelId)
      .setText(request.text());

    if (labels != null && !labels.isEmpty()) {
      for (var label : labels) {
        builder.addLabels(
          io.gravitee.singularitee.protocol.ClassifyLabel.newBuilder()
            .setName(label.name())
            .setDescription(label.description() != null ? label.description() : "")
            .build()
        );
      }
    }

    return client
      .classify(builder.build())
      .map(protoResp -> {
        Map<String, Float> allScores = new LinkedHashMap<>(protoResp.getAllScoresMap());

        var results = protoResp
          .getResultsList()
          .stream()
          .map(r ->
            new ClassifyResult(
              r.getLabel(),
              r.getScore(),
              r.hasToken() ? r.getToken() : null,
              r.hasStart() ? r.getStart() : null,
              r.hasEnd() ? r.getEnd() : null
            )
          )
          .toList();

        return new io.gravitee.singularitee.engine.ClassifyResponse(
          protoResp.getTopLabel(),
          protoResp.getTopScore(),
          allScores,
          results
        );
      });
  }

  @Override
  public Single<List<io.gravitee.singularitee.engine.ClassifyResponse>> rxClassifyBatch(
    List<io.gravitee.singularitee.engine.ClassifyRequest> requests
  ) {
    return rxClassifyBatch(requests, List.of());
  }

  @Override
  public Single<List<io.gravitee.singularitee.engine.ClassifyResponse>> rxClassifyBatch(
    List<io.gravitee.singularitee.engine.ClassifyRequest> requests,
    List<ClassifyLabel> labels
  ) {
    var builder = io.gravitee.singularitee.protocol.ClassifyBatchRequest.newBuilder()
      .setModelId(modelId)
      .addAllTexts(
        requests.stream().map(io.gravitee.singularitee.engine.ClassifyRequest::text).toList()
      );

    if (labels != null && !labels.isEmpty()) {
      for (var label : labels) {
        builder.addLabels(
          io.gravitee.singularitee.protocol.ClassifyLabel.newBuilder()
            .setName(label.name())
            .setDescription(label.description() != null ? label.description() : "")
            .build()
        );
      }
    }

    return client
      .classifyBatch(builder.build())
      .map(batchResp ->
        batchResp
          .getResultsList()
          .stream()
          .map(protoResp -> {
            Map<String, Float> allScores = new LinkedHashMap<>(protoResp.getAllScoresMap());
            var results = protoResp
              .getResultsList()
              .stream()
              .map(r ->
                new ClassifyResult(
                  r.getLabel(),
                  r.getScore(),
                  r.hasToken() ? r.getToken() : null,
                  r.hasStart() ? r.getStart() : null,
                  r.hasEnd() ? r.getEnd() : null
                )
              )
              .toList();
            return new io.gravitee.singularitee.engine.ClassifyResponse(
              protoResp.getTopLabel(),
              protoResp.getTopScore(),
              allScores,
              results
            );
          })
          .toList()
      );
  }

  @Override
  public void close() {
    // Nothing to close — the client is shared
  }
}
