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
package io.gravitee.singularitee.pipeline;

import io.gravitee.node.api.cache.CacheManager;
import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side conversation state for the OpenAI Responses continuation model
 * ({@code previous_response_id} / {@code store}): the transcript a pipeline
 * built — internal tool turns included — plus the todo plan, stored under the
 * response id so the next turn resumes with server-curated history instead of
 * client-replayed messages.
 *
 * <p>Backed by the pluggable gravitee-node {@link CacheManager} (standalone
 * in-memory by default; a distributed backend makes conversations survive
 * restarts and span nodes). Values are small serializable DTOs. Time-to-idle
 * is the conversation timeout.
 */
public final class ConversationStore
  extends CacheBackedStore<ConversationStore.StoredConversation> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConversationStore.class);

  /** Serializable snapshot of one transcript turn. */
  public record StoredTurn(
    String role,
    String content,
    List<StoredToolCall> toolCalls,
    String toolCallId,
    String name
  ) implements Serializable {}

  /** Serializable snapshot of one assistant tool call. */
  public record StoredToolCall(String id, String name, String argumentsJson) implements
    Serializable {}

  /** Everything needed to continue a conversation. */
  public record StoredConversation(
    List<StoredTurn> turns,
    List<TodoSessionStore.SessionTodo> todos,
    String constraints
  ) implements Serializable {}

  /**
   * @param cacheManager   the node cache manager; {@code null} disables storage
   * @param idleTtlSeconds conversation idle timeout; {@code <= 0} disables storage
   * @param maxEntries     upper bound on concurrently stored conversations
   */
  public ConversationStore(CacheManager cacheManager, long idleTtlSeconds, long maxEntries) {
    super(cacheManager, "ai-conversations", idleTtlSeconds, maxEntries);
  }

  /** Returns the stored conversation, or empty when unknown/expired/disabled. */
  public Optional<StoredConversation> get(String responseId) {
    return Optional.ofNullable(lookup(responseId));
  }

  /** Persists the transcript + todo plan + plan constraints under the response id. */
  public void put(
    String responseId,
    List<ChatTurn> turns,
    List<PipelineContext.TodoItem> todos,
    String constraints
  ) {
    if (unusable(responseId) || turns == null) {
      return;
    }
    List<StoredTurn> stored = new ArrayList<>(turns.size());
    for (ChatTurn t : turns) {
      List<StoredToolCall> calls = t
        .toolCalls()
        .stream()
        .map(c -> new StoredToolCall(c.id(), c.name(), c.argumentsJson()))
        .toList();
      stored.add(new StoredTurn(t.role().name(), t.content(), calls, t.toolCallId(), t.name()));
    }
    List<TodoSessionStore.SessionTodo> storedTodos = todos == null
      ? List.of()
      : TodoSessionStore.toSessionTodos(todos);
    cache.put(responseId, new StoredConversation(List.copyOf(stored), storedTodos, constraints));
  }

  /** Rebuilds engine turns from a stored conversation. Turns with an unknown role are skipped. */
  public static List<ChatTurn> toChatTurns(StoredConversation conversation) {
    List<ChatTurn> turns = new ArrayList<>(conversation.turns().size());
    for (StoredTurn t : conversation.turns()) {
      ChatRole role;
      try {
        role = ChatRole.valueOf(t.role());
      } catch (IllegalArgumentException e) {
        LOGGER.warn("Stored conversation carries unknown role '{}' — skipping turn", t.role());
        continue;
      }
      List<ChatTurn.ToolCallTurn> calls = t.toolCalls() == null
        ? List.of()
        : t
          .toolCalls()
          .stream()
          .map(c -> new ChatTurn.ToolCallTurn(c.id(), c.name(), c.argumentsJson()))
          .toList();
      turns.add(new ChatTurn(role, t.content(), List.of(), calls, t.toolCallId(), t.name()));
    }
    return turns;
  }

  /** Rebuilds todo items from a stored conversation. */
  public static List<PipelineContext.TodoItem> toTodoItems(StoredConversation conversation) {
    return TodoSessionStore.toTodoItems(conversation.todos());
  }
}
