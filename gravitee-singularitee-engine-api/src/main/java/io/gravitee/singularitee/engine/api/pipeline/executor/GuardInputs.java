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
package io.gravitee.singularitee.engine.api.pipeline.executor;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardOnEmpty;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves what a guard step inspects and writes its redactions back.
 *
 * <p>A guard either reads one context field ({@code input_field} set to anything but
 * {@code prompt}) or the request itself, in which case its {@link GuardScope} selects the turns.
 * A request sent as a bare prompt has no transcript, so the {@code prompt} field stands in for it.
 *
 * @author GraviteeSource Team
 */
public final class GuardInputs {

  /** One text a guard inspects: a transcript turn ({@code turn >= 0}) or a context field. */
  public record Target(String text, int turn) {
    static final int FIELD = -1;

    public boolean isTurn() {
      return turn >= 0;
    }
  }

  private GuardInputs() {}

  /** Whether the guard reads a named context field rather than the request's turns. */
  public static boolean readsField(String inputField) {
    return (
      inputField != null && !inputField.isBlank() && !inputField.equals(PipelineContext.KEY_PROMPT)
    );
  }

  /** The non-blank texts the guard inspects, in transcript order; empty when there is none. */
  public static List<Target> resolve(String inputField, GuardScope scope, PipelineContext pctx) {
    if (readsField(inputField)) return fieldTarget(pctx.get(inputField));
    List<ChatTurn> messages = pctx.messages();
    if (messages == null || messages.isEmpty()) return fieldTarget(
      pctx.get(PipelineContext.KEY_PROMPT)
    );

    List<Target> targets = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      ChatTurn turn = messages.get(i);
      if (inScope(turn.role(), scope) && turn.content() != null && !turn.content().isBlank()) {
        targets.add(new Target(turn.content(), i));
      }
    }
    if (scope == GuardScope.LAST_USER && !targets.isEmpty()) return List.of(targets.getLast());
    return targets;
  }

  /**
   * Writes redacted texts back: each redacted turn's content is replaced and {@code prompt} is
   * kept equal to the last user turn, so no downstream template sees the original. A redacted
   * field target is the caller's to publish.
   */
  public static void applyRedactions(PipelineContext pctx, Map<Target, String> redacted) {
    if (redacted.isEmpty()) return;
    List<ChatTurn> messages = pctx.messages();
    if (messages == null || messages.isEmpty()) {
      redacted
        .entrySet()
        .stream()
        .filter(e -> !e.getKey().isTurn())
        .findFirst()
        .ifPresent(e -> {
          if (e.getKey().text().equals(pctx.get(PipelineContext.KEY_PROMPT))) {
            pctx.set(PipelineContext.KEY_PROMPT, e.getValue());
          }
        });
      return;
    }
    List<ChatTurn> rewritten = new ArrayList<>(messages);
    boolean changed = false;
    for (var e : redacted.entrySet()) {
      Target target = e.getKey();
      if (!target.isTurn() || target.turn() >= rewritten.size()) continue;
      ChatTurn t = rewritten.get(target.turn());
      rewritten.set(
        target.turn(),
        new ChatTurn(t.role(), e.getValue(), t.media(), t.toolCalls(), t.toolCallId(), t.name())
      );
      changed = true;
    }
    if (!changed) return;
    pctx.setMessages(List.copyOf(rewritten));
    ChatTurn.lastUserContent(rewritten).ifPresent(p -> pctx.set(PipelineContext.KEY_PROMPT, p));
  }

  /** The text that stands for the request in single-value outputs: the last user turn's. */
  public static String primaryText(
    List<Target> targets,
    PipelineContext pctx,
    Map<Target, String> redacted
  ) {
    Target primary = null;
    List<ChatTurn> messages = pctx.messages();
    for (Target t : targets) {
      if (
        !t.isTurn() ||
        (messages != null &&
          t.turn() < messages.size() &&
          messages.get(t.turn()).role() == ChatRole.USER)
      ) {
        primary = t;
      }
    }
    if (primary == null) primary = targets.getLast();
    return redacted.getOrDefault(primary, primary.text());
  }

  /**
   * Parses a guard's {@code scope}. A scope selects turns, so it is refused alongside an
   * {@code input_field} naming another field rather than silently ignored.
   */
  public static GuardScope parseScope(String stepId, String inputField, String scope) {
    if (scope != null && !scope.isBlank() && readsField(inputField)) {
      throw new IllegalArgumentException(
        "Step '" +
          stepId +
          "': scope selects the request's turns and cannot be combined with input_field '" +
          inputField +
          "'"
      );
    }
    try {
      return GuardScope.parse(scope);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Step '" + stepId + "': " + e.getMessage(), e);
    }
  }

  /** Parses a guard's {@code on_empty}; its default depends on the guard's action. */
  public static GuardOnEmpty parseOnEmpty(String stepId, String onEmpty, GuardAction action) {
    try {
      return GuardOnEmpty.parse(onEmpty, action);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Step '" + stepId + "': " + e.getMessage(), e);
    }
  }

  private static List<Target> fieldTarget(String value) {
    return value == null || value.isBlank() ? List.of() : List.of(new Target(value, Target.FIELD));
  }

  private static boolean inScope(ChatRole role, GuardScope scope) {
    return switch (scope) {
      case LAST_USER, USER_TURNS -> role == ChatRole.USER;
      case CONVERSATION -> role != ChatRole.SYSTEM;
    };
  }
}
