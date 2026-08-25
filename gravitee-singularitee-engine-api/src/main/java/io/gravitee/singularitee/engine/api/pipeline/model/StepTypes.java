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
package io.gravitee.singularitee.engine.api.pipeline.model;

import java.util.Set;

/**
 * Canonical step type strings. Step identity is the string a workspace writes as
 * {@code type:}; the executor registry, availability checks and the workspace loader
 * all key on it. The constants here name the core steps; plugin steps introduce new
 * strings without touching this class.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class StepTypes {

  public static final String INFER = "infer";
  public static final String CLASSIFY = "classify";
  public static final String EMBED = "embed";
  public static final String ROUTE = "route";
  public static final String GUARD = "guard";
  public static final String LLM_GUARD = "llm_guard";
  public static final String BREAK = "break";
  public static final String LOOP = "loop";
  public static final String SUB_PIPELINE = "sub_pipeline";
  public static final String REGEX_GUARD = "regex_guard";
  public static final String TOOL_SELECT = "tool_select";
  public static final String TODO = "todo";

  /** The core step types every server must be able to execute. */
  public static final Set<String> CORE = Set.of(
    INFER,
    CLASSIFY,
    EMBED,
    ROUTE,
    GUARD,
    LLM_GUARD,
    BREAK,
    LOOP,
    SUB_PIPELINE,
    REGEX_GUARD,
    TOOL_SELECT,
    TODO
  );

  private StepTypes() {}
}
