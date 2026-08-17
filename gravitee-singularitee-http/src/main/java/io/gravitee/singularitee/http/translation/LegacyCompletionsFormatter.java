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
package io.gravitee.singularitee.http.translation;

import static io.gravitee.singularitee.http.translation.ResponseJsonWriters.usageChunk;
import static io.gravitee.singularitee.http.translation.ResponseJsonWriters.writeLogprobs;
import static io.gravitee.singularitee.http.translation.ResponseJsonWriters.writeUsage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.http.json.Utils;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Formats a token stream into legacy OpenAI Completions shapes ({@code text_completion}): SSE
 * streaming chunks and the non-streaming JSON response.
 */
public final class LegacyCompletionsFormatter {

  private static final ThreadLocal<ObjectMapper> OBJECT_MAPPER = Utils.OBJECT_MAPPER;

  private LegacyCompletionsFormatter() {}

  public static Flowable<ServerEvent> completionStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal
  ) {
    // Deferred: per-subscription state must be created per subscription, not per
    // factory call, or a re-subscribe replays corrupted state.
    return Flowable.defer(() ->
      completionStreamEventsOnce(tokenStream, modelName, includeUsage, onFinal)
    );
  }

  private static Flowable<ServerEvent> completionStreamEventsOnce(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal
  ) {
    long created = Instant.now().getEpochSecond();
    String responseId = "cmpl-" + created;

    // Progress updates are not part of the Completions contract — dropped.
    return tokenStream
      .filter(t -> t.progress() == null)
      .flatMap(token -> {
        if (token.isFinal()) {
          if (onFinal != null) {
            onFinal.accept(token);
          }
          ObjectNode finalChunk = completionChunk(
            responseId,
            created,
            modelName,
            null,
            token.finishReason()
          );
          List<ServerEvent> events = new ArrayList<>();
          events.add(new ServerEvent(finalChunk.toString()));
          if (includeUsage) {
            events.add(
              new ServerEvent(usageChunk(responseId, created, modelName, false, token).toString())
            );
          }
          events.add(new ServerEvent("[DONE]"));
          return Flowable.fromIterable(events);
        }
        String text = token.tool() != null ? token.tool() : token.token();
        return Flowable.just(
          new ServerEvent(completionChunk(responseId, created, modelName, text, null).toString())
        );
      });
  }

  public static ObjectNode buildCompletionResponse(
    String modelName,
    SequenceAccumulator accumulator
  ) {
    ObjectNode response = OBJECT_MAPPER.get().createObjectNode();
    response.put("id", accumulator.responseId("cmpl-"));
    response.put("object", "text_completion");
    response.put("created", accumulator.created());
    response.put("model", modelName);
    ArrayNode choices = response.putArray("choices");
    ObjectNode choice = choices.addObject();
    choice.put("index", 0);
    choice.put("text", accumulator.content());
    writeLogprobs(choice, accumulator.logprobs());
    choice.put("finish_reason", accumulator.finishReason());
    writeUsage(response, accumulator);
    return response;
  }

  private static ObjectNode completionChunk(
    String id,
    long created,
    String model,
    String content,
    String finishReason
  ) {
    ObjectNode chunk = OBJECT_MAPPER.get().createObjectNode();
    chunk.put("id", id);
    chunk.put("object", "text_completion");
    chunk.put("created", created);
    chunk.put("model", model);
    ArrayNode choices = chunk.putArray("choices");
    ObjectNode choice = choices.addObject();
    choice.put("index", 0);
    if (content != null) {
      choice.put("text", content);
    }
    choice.putNull("logprobs");
    if (finishReason != null) {
      choice.put("finish_reason", finishReason);
    } else {
      choice.putNull("finish_reason");
    }
    return chunk;
  }
}
