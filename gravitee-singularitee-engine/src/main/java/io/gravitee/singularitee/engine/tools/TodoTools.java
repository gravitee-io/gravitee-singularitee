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
package io.gravitee.singularitee.engine.tools;

import io.gravitee.singularitee.protocol.ToolDefinition;
import io.gravitee.singularitee.protocol.ToolParameterDef;
import java.util.List;
import java.util.Set;

/**
 * The server-owned todo tool definitions injected into infer steps when a
 * pipeline contains a {@code STEP_TYPE_TODO} step. Calls to these tools are
 * executed by {@code TodoStepExecutor} and never reach the client.
 */
public final class TodoTools {

  public static final String SET_TODOS = "set_todos";
  public static final String COMPLETE_TODO = "complete_todo";
  public static final String ASK_USER = "ask_user";

  /** Names of the tools the server owns; used to partition extracted calls. */
  public static final Set<String> NAMES = Set.of(SET_TODOS, COMPLETE_TODO, ASK_USER);

  /**
   * Server tools a CLIENT may take over by declaring a tool of the same name:
   * the server then passes the call through as a normal function_call (the
   * client's schema is what the model sees) instead of executing it. Plan
   * state tools are never delegable — a client may not own the todo list.
   */
  public static final Set<String> DELEGABLE = Set.of(ASK_USER);

  private TodoTools() {}

  /** The tool definitions to register on the pipeline context. */
  public static List<ToolDefinition> definitions() {
    return List.of(
      ToolDefinition.newBuilder()
        .setName(SET_TODOS)
        .setDescription(
          "Replace your todo plan for this task. Call this FIRST to decompose the task, " +
            "and again only if the plan must change. Each item: {\"id\": \"1\", \"title\": " +
            "\"...\"}. Items start pending; the first becomes in_progress automatically."
        )
        .addParameters(
          ToolParameterDef.newBuilder()
            .setName("todos")
            .setType("array")
            .setDescription(
              "The full ordered plan, as objects with string fields 'id' and 'title'."
            )
            .setRequired(true)
        )
        .addParameters(
          ToolParameterDef.newBuilder()
            .setName("constraints")
            .setType("string")
            .setDescription(
              "Every decision the user has made (language, tools, frameworks, features, " +
                "limits) as one short paragraph. Always fill it when the user answered " +
                "clarifying questions."
            )
        )
        .build(),
      ToolDefinition.newBuilder()
        .setName(COMPLETE_TODO)
        .setDescription(
          "Mark one todo item done after you finished it. The next pending item becomes " +
            "in_progress automatically. Put the COMPLETE result text in note - it is " +
            "recorded as the item's proof and is the only place the work survives."
        )
        .addParameters(
          ToolParameterDef.newBuilder()
            .setName("id")
            .setType("string")
            .setDescription("The id of the item you completed.")
            .setRequired(true)
        )
        .addParameters(
          ToolParameterDef.newBuilder()
            .setName("note")
            .setType("string")
            .setDescription(
              "The complete result of the item (recorded as its proof; later steps see " +
                "only this, not your message text)."
            )
        )
        .build(),
      ToolDefinition.newBuilder()
        .setName(ASK_USER)
        .setDescription(
          "Ask the user a question when you cannot proceed without information from them. " +
            "The conversation pauses and the user's reply arrives as the next message. Ask " +
            "ONE precise question; your plan is preserved and resumes afterwards."
        )
        .addParameters(
          ToolParameterDef.newBuilder()
            .setName("question")
            .setType("string")
            .setDescription("The question to put to the user.")
            .setRequired(true)
        )
        .build()
    );
  }
}
