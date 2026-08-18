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

/**
 * Lifecycle state of one engine-managed todo item.
 *
 * <p>The wire representation ({@link #wireName()}) is the lowercase string
 * used at every serialization boundary — the proto {@code TodoItem.status},
 * the session/conversation cache DTOs and tool-call JSON. Inside the engine
 * the enum is the single source of truth.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum TodoStatus {
  PENDING("pending"),
  IN_PROGRESS("in_progress"),
  DONE("done");

  private final String wireName;

  TodoStatus(String wireName) {
    this.wireName = wireName;
  }

  /** The lowercase string used on the wire and in stored DTOs. */
  public String wireName() {
    return wireName;
  }

  /**
   * Parses a wire string, defaulting to {@link #PENDING} for {@code null} or
   * unknown values.
   */
  public static TodoStatus fromWire(String value) {
    for (TodoStatus status : values()) {
      if (status.wireName.equals(value)) {
        return status;
      }
    }
    return PENDING;
  }

  /** Whether the given wire string names a valid status. */
  public static boolean isValidWireName(String value) {
    for (TodoStatus status : values()) {
      if (status.wireName.equals(value)) {
        return true;
      }
    }
    return false;
  }
}
