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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.http.json.Utils;
import io.gravitee.singularitee.http.translation.wire.FunctionCallItem;
import io.gravitee.singularitee.http.translation.wire.ProgressPayload;
import io.gravitee.singularitee.protocol.ResponseProgress;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Builders for the individual Responses API stream events and output items. */
final class ResponsesEventFactory {

  private static final ThreadLocal<ObjectMapper> OBJECT_MAPPER = Utils.OBJECT_MAPPER;

  private ResponsesEventFactory() {}

  /**
   * Renders a {@code RESPONSE_EVENT_TYPE_PROGRESS} update as a gravitee-namespaced
   * Responses-API event: {@code {"type":"gravitee.progress","sequence_number":n,
   * "step_id":…, "todos":[{id,title,status}…], "completed":n, "total":n}}.
   */
  static ServerEvent progressEvent(AtomicLong seq, ResponseProgress progress) {
    var payload = new ProgressPayload(seq.getAndIncrement(), progress);
    return new ServerEvent(OBJECT_MAPPER.get().valueToTree(payload).toString());
  }

  /** A Responses-API {@code function_call} output item. */
  static ObjectNode functionCallItem(ParsedToolCall tc, String status) {
    return OBJECT_MAPPER.get().valueToTree(new FunctionCallItem(tc, status));
  }

  /** A minimal Responses object ({@code id/object/status/created_at/model}); caller adds output/usage. */
  static ObjectNode responsesObject(String id, String status, long created, String model) {
    ObjectNode r = OBJECT_MAPPER.get().createObjectNode();
    r.put("id", id);
    r.put("object", "response");
    r.put("status", status);
    r.put("created_at", created);
    r.put("model", model);
    return r;
  }

  /** A Responses {@code message} output item; {@code text == null} → empty content (item still open). */
  static ObjectNode responsesMessageItem(String id, String status, String text) {
    ObjectNode item = OBJECT_MAPPER.get().createObjectNode();
    item.put("id", id);
    item.put("type", "message");
    item.put("status", status);
    item.put("role", "assistant");
    ArrayNode content = item.putArray("content");
    if (text != null) {
      ObjectNode part = content.addObject();
      part.put("type", "output_text");
      part.put("text", text);
      part.putArray("annotations");
    }
    return item;
  }

  /**
   * Maps a guard-failure message to the Responses error code. Stored-conversation misses have a
   * dedicated code; everything else is a generic server error.
   */
  static String errorCode(String guardMessage) {
    return guardMessage.startsWith("No stored response")
      ? "previous_response_not_found"
      : "server_error";
  }

  /** Writes the failed status, {@code error} object and empty {@code output} onto a response. */
  static void writeFailure(ObjectNode response, String guardMessage) {
    ObjectNode error = response.putObject("error");
    error.put("code", errorCode(guardMessage));
    error.put("message", guardMessage);
    response.putArray("output");
  }

  /** Terminal {@code response.failed} event for guard-only failures on the streaming paths. */
  static ServerEvent responseFailedEvent(
    AtomicLong seq,
    String responseId,
    long created,
    String modelName,
    String guardMessage
  ) {
    ObjectNode failed = responsesObject(responseId, "failed", created, modelName);
    writeFailure(failed, guardMessage);
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", "response.failed");
    event.put("sequence_number", seq.getAndIncrement());
    event.set("response", failed);
    return new ServerEvent(event.toString());
  }

  /** Wraps a response object in a top-level Responses stream event ({@code response.*}). */
  static ServerEvent responsesEvent(String type, AtomicLong seq, ObjectNode response) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", type);
    event.put("sequence_number", seq.getAndIncrement());
    event.set("response", response);
    return new ServerEvent(event.toString());
  }

  static ServerEvent outputItemEvent(
    String type,
    AtomicLong seq,
    int outputIndex,
    ObjectNode item
  ) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", type);
    event.put("sequence_number", seq.getAndIncrement());
    event.put("output_index", outputIndex);
    event.set("item", item);
    return new ServerEvent(event.toString());
  }

  static ServerEvent contentPartEvent(
    String type,
    AtomicLong seq,
    String itemId,
    int outputIndex,
    String text
  ) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", type);
    event.put("sequence_number", seq.getAndIncrement());
    event.put("item_id", itemId);
    event.put("output_index", outputIndex);
    event.put("content_index", 0);
    ObjectNode part = event.putObject("part");
    part.put("type", "output_text");
    part.put("text", text);
    part.putArray("annotations");
    return new ServerEvent(event.toString());
  }

  static ServerEvent outputTextDeltaEvent(
    AtomicLong seq,
    String itemId,
    int outputIndex,
    String delta
  ) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", "response.output_text.delta");
    event.put("sequence_number", seq.getAndIncrement());
    event.put("item_id", itemId);
    event.put("output_index", outputIndex);
    event.put("content_index", 0);
    event.put("delta", delta);
    return new ServerEvent(event.toString());
  }

  static ServerEvent outputTextDoneEvent(
    AtomicLong seq,
    String itemId,
    int outputIndex,
    String text
  ) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", "response.output_text.done");
    event.put("sequence_number", seq.getAndIncrement());
    event.put("item_id", itemId);
    event.put("output_index", outputIndex);
    event.put("content_index", 0);
    event.put("text", text);
    return new ServerEvent(event.toString());
  }

  /** A Responses {@code reasoning} output item; {@code text == null}/empty → empty summary. */
  static ObjectNode reasoningItem(String id, String text) {
    ObjectNode item = OBJECT_MAPPER.get().createObjectNode();
    item.put("id", id);
    item.put("type", "reasoning");
    ArrayNode summary = item.putArray("summary");
    if (text != null && !text.isEmpty()) {
      ObjectNode s = summary.addObject();
      s.put("type", "summary_text");
      s.put("text", text);
    }
    return item;
  }

  static ServerEvent reasoningSummaryPartEvent(
    String type,
    AtomicLong seq,
    String itemId,
    String text
  ) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", type);
    event.put("sequence_number", seq.getAndIncrement());
    event.put("item_id", itemId);
    event.put("output_index", 0);
    event.put("summary_index", 0);
    ObjectNode part = event.putObject("part");
    part.put("type", "summary_text");
    part.put("text", text);
    return new ServerEvent(event.toString());
  }

  static ServerEvent reasoningSummaryDelta(AtomicLong seq, String itemId, String delta) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", "response.reasoning_summary_text.delta");
    event.put("sequence_number", seq.getAndIncrement());
    event.put("item_id", itemId);
    event.put("output_index", 0);
    event.put("summary_index", 0);
    event.put("delta", delta);
    return new ServerEvent(event.toString());
  }

  private static ServerEvent reasoningSummaryDone(AtomicLong seq, String itemId, String text) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", "response.reasoning_summary_text.done");
    event.put("sequence_number", seq.getAndIncrement());
    event.put("item_id", itemId);
    event.put("output_index", 0);
    event.put("summary_index", 0);
    event.put("text", text);
    return new ServerEvent(event.toString());
  }

  /** Emits the three closing events for a reasoning item (summary done → part done → item done). */
  static void closeReasoning(
    List<ServerEvent> events,
    AtomicLong seq,
    String reasoningId,
    String text
  ) {
    events.add(reasoningSummaryDone(seq, reasoningId, text));
    events.add(
      reasoningSummaryPartEvent("response.reasoning_summary_part.done", seq, reasoningId, text)
    );
    events.add(
      outputItemEvent("response.output_item.done", seq, 0, reasoningItem(reasoningId, text))
    );
  }

  /**
   * Emits a full message-item lifecycle for a buffered text answer:
   * output_item.added -> content_part.added -> output_text.delta ->
   * output_text.done -> content_part.done -> output_item.done. Stock Responses
   * SDKs assemble the response from these item events and render nothing for a
   * bare output_text.delta.
   */
  static void emitBufferedTextItem(
    List<ServerEvent> events,
    AtomicLong seq,
    long created,
    String text,
    int outputIndex
  ) {
    String msgId = "msg-" + created;
    events.add(
      outputItemEvent(
        "response.output_item.added",
        seq,
        outputIndex,
        responsesMessageItem(msgId, "in_progress", null)
      )
    );
    events.add(contentPartEvent("response.content_part.added", seq, msgId, outputIndex, ""));
    events.add(outputTextDeltaEvent(seq, msgId, outputIndex, text));
    events.add(outputTextDoneEvent(seq, msgId, outputIndex, text));
    events.add(contentPartEvent("response.content_part.done", seq, msgId, outputIndex, text));
    events.add(
      outputItemEvent(
        "response.output_item.done",
        seq,
        outputIndex,
        responsesMessageItem(msgId, "completed", text)
      )
    );
  }

  static void addTextOutputItem(ArrayNode output, String text) {
    ObjectNode item = output.addObject();
    item.put("type", "message");
    item.put("role", "assistant");
    item.put("status", "completed");
    ArrayNode content = item.putArray("content");
    ObjectNode textPart = content.addObject();
    textPart.put("type", "output_text");
    textPart.put("text", text);
  }
}
