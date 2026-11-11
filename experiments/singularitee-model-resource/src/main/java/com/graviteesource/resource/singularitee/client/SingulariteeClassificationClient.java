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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graviteesource.resource.singularitee.configuration.RemoteModelConfiguration;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Singularitee's classify protocol adapted to the gateway's classification contract. */
public final class SingulariteeClassificationClient implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private final RemoteModelConfiguration configuration;
  private final HttpClient client;
  private final Vertx vertx;

  public SingulariteeClassificationClient(
    Vertx vertx,
    RemoteModelConfiguration configuration
  ) {
    this.configuration = configuration;
    this.vertx = java.util.Objects.requireNonNull(vertx);
    client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofMillis(configuration.timeoutMs()))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
  }

  public Single<ClassifierResults> classify(
    PromptInput input,
    Map<String, String> headers,
    String capability
  ) {
    if (
      !List.of("token-classification", "text-classification").contains(
        capability
      )
    ) return Single.error(
      new IllegalArgumentException("Unsupported classification capability")
    );
    return Single.<ClassifierResults>create(emitter -> {
      try {
        if (
          configuration.authorization() != null &&
          !configuration.authorization().isBlank() &&
          (headers.get("Authorization") == null ||
            headers.get("Authorization").isBlank())
        ) {
          emitter.tryOnError(
            new IllegalStateException(
              "Model authorization could not be resolved"
            )
          );
          return;
        }
        var endpoint =
          configuration.endpoint().replaceAll("/+$", "") + "/v1/classify";
        var request = HttpRequest.newBuilder(URI.create(endpoint))
          .timeout(Duration.ofMillis(configuration.timeoutMs()))
          .header("Content-Type", "application/json")
          .POST(
            HttpRequest.BodyPublishers.ofByteArray(
              JSON.writeValueAsBytes(
                Map.of(
                  "model",
                  configuration.modelId(),
                  "input",
                  input.promptContent()
                )
              )
            )
          );
        headers.forEach(request::header);
        var response = client.sendAsync(request.build(), ignored ->
          new BoundedBodySubscriber()
        );
        emitter.setCancellable(() -> response.cancel(true));
        response.whenComplete((result, failure) -> {
          if (emitter.isDisposed()) return;
          if (failure != null) emitter.tryOnError(
            new IllegalStateException("Classification service unavailable")
          );
          else {
            try {
              if (result.statusCode() != 200) throw new IllegalStateException(
                "Classification service returned HTTP " + result.statusCode()
              );
              emitter.onSuccess(
                parse(result.body(), configuration.modelId(), capability)
              );
            } catch (Exception error) {
              emitter.tryOnError(error);
            }
          }
        });
      } catch (Exception error) {
        // HTTP header validation errors can include resolved credential values.
        emitter.tryOnError(
          new IllegalStateException("Classification request could not be sent")
        );
      }
    })
      .timeout(configuration.timeoutMs(), TimeUnit.MILLISECONDS)
      .observeOn(io.vertx.rxjava3.core.RxHelper.scheduler(vertx));
  }

  static ClassifierResults parse(
    byte[] body,
    String modelId,
    String capability
  ) {
    try {
      var root = JSON.readTree(body);
      if (
        root == null ||
        !modelId.equals(root.path("model").asText()) ||
        !root.path("results").isArray() ||
        root.path("results").size() != 1
      ) throw invalidResponse();
      var result = root.path("results").get(0);
      if (!result.path("scores").isObject()) throw invalidResponse();
      if ("text-classification".equals(capability)) {
        if (result.path("scores").isEmpty()) throw invalidResponse();
        var labels = new ArrayList<ClassifierResults.ClassifierResult>();
        for (var entry : result.path("scores").properties()) {
          var score = entry.getValue();
          if (
            entry.getKey().isBlank() ||
            !score.isNumber() ||
            !Double.isFinite(score.asDouble()) ||
            score.asDouble() < 0 ||
            score.asDouble() > 1
          ) throw invalidResponse();
          labels.add(
            new ClassifierResults.ClassifierResult(
              entry.getKey(),
              score.floatValue(),
              null,
              null,
              null
            )
          );
        }
        return new ClassifierResults(labels);
      }
      var spans = result.path("spans");
      var results = new ArrayList<ClassifierResults.ClassifierResult>();
      if (spans.isMissingNode() || (spans.isArray() && spans.isEmpty())) {
        // Singularitee omits spans when the token classifier finds no entities.
        // Non-empty entity scores without offsets cannot satisfy this resource's contract.
        if (!result.path("scores").isEmpty()) throw invalidResponse();
        return new ClassifierResults(results);
      }
      if (!spans.isArray()) throw invalidResponse();
      for (var span : spans) {
        var label = span.path("label");
        var score = span.path("score");
        var start = span.path("start");
        var end = span.path("end");
        if (
          !label.isTextual() ||
          label.asText().isBlank() ||
          !score.isNumber() ||
          !Double.isFinite(score.asDouble()) ||
          score.asDouble() < 0 ||
          score.asDouble() > 1 ||
          !start.isIntegralNumber() ||
          !end.isIntegralNumber() ||
          !start.canConvertToInt() ||
          !end.canConvertToInt() ||
          start.asInt() < 0 ||
          end.asInt() < start.asInt()
        ) throw invalidResponse();
        if (!"O".equals(label.asText())) results.add(
          new ClassifierResults.ClassifierResult(
            label.asText(),
            score.floatValue(),
            span.path("token").isTextual() ? span.path("token").asText() : null,
            start.asInt(),
            end.asInt()
          )
        );
      }
      return new ClassifierResults(results);
    } catch (Exception error) {
      // Never propagate response bodies, which may contain the inspected PII.
      throw invalidResponse();
    }
  }

  private static IllegalStateException invalidResponse() {
    return new IllegalStateException("Invalid classification response");
  }

  @Override
  public void close() {
    client.shutdownNow();
  }

  private static final class BoundedBodySubscriber
    implements HttpResponse.BodySubscriber<byte[]> {

    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    public CompletionStage<byte[]> getBody() {
      return body;
    }

    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
          subscription.cancel();
          body.completeExceptionally(
            new IllegalStateException("Classification response exceeds limit")
          );
          return;
        }
        var chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      body.completeExceptionally(error);
    }

    public void onComplete() {
      body.complete(bytes.toByteArray());
    }
  }
}
