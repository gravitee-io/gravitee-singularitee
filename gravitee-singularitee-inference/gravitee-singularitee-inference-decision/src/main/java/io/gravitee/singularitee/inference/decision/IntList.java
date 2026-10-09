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
package io.gravitee.singularitee.inference.decision;

import java.util.Arrays;

/** A growable {@code int[]}, for assembling token sequences without boxing. */
final class IntList {

  private int[] data;
  private int size;

  IntList(int capacity) {
    data = new int[Math.max(capacity, 8)];
  }

  void add(int value) {
    ensure(size + 1);
    data[size++] = value;
  }

  void addRange(int[] values, int from, int to) {
    if (to <= from) return;
    ensure(size + to - from);
    System.arraycopy(values, from, data, size, to - from);
    size += to - from;
  }

  int size() {
    return size;
  }

  int[] toArray() {
    return Arrays.copyOf(data, size);
  }

  private void ensure(int capacity) {
    if (capacity > data.length) data = Arrays.copyOf(data, Math.max(capacity, data.length * 2));
  }
}
