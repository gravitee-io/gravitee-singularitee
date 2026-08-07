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
package io.gravitee.singularitee.inference.math.vanilla;

import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.rangeClosed;
import static org.junit.jupiter.api.Assertions.*;

import io.gravitee.singularitee.inference.math.api.GioMaths;
import java.nio.FloatBuffer;
import java.util.Collections;
import org.apache.commons.math3.util.FastMath;
import org.junit.jupiter.api.Test;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class NativeMathTest {

  private static final GioMaths INSTANCE = NativeMath.INSTANCE;
  private static final float[][] MATRIX = {
    { 1f, 2f, 3f, 4f, 5f, 7f, 8f, 9f, 10f, 11f },
    { 1f, 3f, 5f, 7f, 9f, 11f, 13f, 15f, 17f, 19f, 21f },
    { 2f, 4f, 6f, 8f, 10f, 12f, 14f, 16f, 18f, 20f, 22f },
    { 2f, 3f, 5f, 7f, 11f, 13f, 17f, 19f, 23f, 29f, 31f },
  };

  @Test
  void must_compute_cosine_similarity() {
    final float[] v1 = { 1.5f, 3.0f, 4.75f, 6.5f, 8.75f, 10.75f, 13.0f, 14.75f, 17.0f, 19.75f };
    final float[] v2 = { 1.6f, 2.9f, 4.7f, 6.55f, 8.7f, 10.73f, 13.3f, 14.5f, 17.1f, 16.75f };
    final float cosineSimilarity = INSTANCE.cosineSimilarity(v1, v2);

    assertTrue(cosineSimilarity >= 0.99);
    assertEquals((1f + cosineSimilarity) / 2, INSTANCE.cosineScore(v1, v2));
  }

  @Test
  void must_compute_mean() {
    final float[] expected = {
      1.5f,
      3.0f,
      4.75f,
      6.5f,
      8.75f,
      10.75f,
      13.0f,
      14.75f,
      17.0f,
      19.75f,
    };
    final float[] mean = INSTANCE.mean(MATRIX);

    assertArrayEquals(expected, mean);
  }

  @Test
  void must_compute_weighted_mean() {
    final float[] weights = { 1f, 2f, 3f, 4f, 5f, 7f, 8f, 9f, 10f, 11f };
    final float[] expected = { 1.7f, 3.2f, 5.1f, 7.0f, 9.7f, 11.7f, 14.4f, 16.3f, 19.0f, 22.5f };
    final float[] weightedMean = INSTANCE.weightedMean(MATRIX, weights);

    assertArrayEquals(expected, weightedMean);
  }

  @Test
  void must_normalize() {
    final float[] vector = { 1f, 2f, 3f, 4f, 5f, 7f, 8f, 9f, 10f, 11f };
    final float[] expected = {
      0.05f,
      0.1f,
      0.14f,
      0.19f,
      0.24f,
      0.33f,
      0.37f,
      0.42f,
      0.47f,
      0.51f,
    };
    final float[] normalize = INSTANCE.normalize(vector);
    assertArrayEquals(expected, ceil(normalize));
  }

  @Test
  void must_compute_euclidean_distance_same_vector() {
    final float[] v1 = { 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f };

    assertEquals(0.0f, INSTANCE.euclideanDistance(v1, v1));
  }

  @Test
  void must_compute_euclidean_distance() {
    final float[] v1 = { 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f };
    final float[] v2 = { 11f, 10f, 9f, 8f, 7f, 6f, 5f, 4f, 3f, 2f, 1f };

    assertEquals(20.98f, ceil(INSTANCE.euclideanDistance(v1, v2)));
  }

  @Test
  void must_compute_max() {
    var floatList = rangeClosed(0, 10000)
      .mapToObj(i -> (float) i)
      .collect(toList());
    Collections.shuffle(floatList);

    final float[] array = floatList
      .stream()
      .collect(() -> FloatBuffer.allocate(floatList.size()), FloatBuffer::put, (left, right) -> {})
      .array();
    assertEquals(10000, INSTANCE.max(array));
  }

  @Test
  void must_do_softmax() {
    final float[] v1 = { 1f, 2f, 3f, 10f, 11f };
    final float[] expected = { 0.01f, 0.01f, 0.01f, 0.27f, 0.74f };
    assertArrayEquals(expected, ceil(INSTANCE.softmax(v1)));
  }

  @Test
  void must_do_sigmoid() {
    final float[] v1 = { 2.1f, -0.8f, 0.0f, 4.5f };
    final float[] expected = { 0.9f, 0.32f, 0.5f, 0.99f };
    assertArrayEquals(expected, ceil(INSTANCE.sigmoid(v1)));
  }

  private float[] ceil(float[] vector) {
    float[] result = new float[vector.length];
    for (int i = 0; i < vector.length; i++) {
      result[i] = (float) (FastMath.ceil(vector[i] * 100) / 100);
    }
    return result;
  }

  private float ceil(float scalar) {
    return (float) (FastMath.ceil(scalar * 100) / 100);
  }
}
