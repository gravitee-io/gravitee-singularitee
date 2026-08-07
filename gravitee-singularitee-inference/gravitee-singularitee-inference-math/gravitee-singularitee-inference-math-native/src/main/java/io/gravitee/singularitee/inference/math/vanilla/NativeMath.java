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

import io.gravitee.singularitee.inference.math.api.GioMaths;
import org.apache.commons.math3.util.FastMath;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class NativeMath implements GioMaths {

  private NativeMath() {}

  public static final GioMaths INSTANCE = new NativeMath();

  @Override
  public float cosineSimilarity(float[] v1, float[] v2) {
    if (v1.length != v2.length) {
      throw new IllegalArgumentException("Vectors must have the same length");
    }

    float dotProduct = 0.0f;
    float magnitudeA = 0.0f;
    float magnitudeB = 0.0f;

    for (int i = 0; i < v1.length; i++) {
      dotProduct += v1[i] * v2[i];
      magnitudeA += v1[i] * v1[i];
      magnitudeB += v2[i] * v2[i];
    }

    if (magnitudeA == 0 || magnitudeB == 0) {
      return 0.0f;
    }

    return (float) (dotProduct / (FastMath.sqrt(magnitudeA) * FastMath.sqrt(magnitudeB)));
  }

  @Override
  public float euclideanDistance(float[] v1, float[] v2) {
    if (v1.length != v2.length) {
      throw new IllegalArgumentException("Both points must have the same dimension.");
    }

    float sum = 0.0f;
    for (int i = 0; i < v1.length; i++) {
      float diff = v1[i] - v2[i];
      sum += diff * diff;
    }

    return (float) FastMath.sqrt(sum);
  }

  @Override
  public float[] mean(float[][] matrix) {
    int rows = matrix.length;
    int cols = matrix[0].length;

    float[] meanVector = new float[cols];
    for (float[] vector : matrix) {
      for (int col = 0; col < cols; col++) {
        meanVector[col] += vector[col];
      }
    }
    for (int col = 0; col < cols; col++) {
      meanVector[col] /= rows;
    }

    return meanVector;
  }

  @Override
  public float normL2(float[] vector) {
    float sumSquare = 0;
    for (float v : vector) {
      sumSquare += v * v;
    }
    return (float) FastMath.sqrt(sumSquare);
  }

  @Override
  public float[] normalize(float[] vector) {
    final float norm = normL2(vector);

    float[] normalizedVector = new float[vector.length];
    for (int i = 0; i < vector.length; i++) {
      normalizedVector[i] = vector[i] / norm;
    }

    return normalizedVector;
  }

  @Override
  public float[] softmax(float[] vector) {
    float maxLogit = max(vector);
    float sum = 0.0f;
    float[] output = new float[vector.length];
    for (int i = 0; i < vector.length; i++) {
      output[i] = (float) FastMath.exp(vector[i] - maxLogit);
      sum += output[i];
    }

    for (int i = 0; i < vector.length; i++) {
      output[i] /= sum;
    }

    return output;
  }

  @Override
  public float[] sigmoid(float[] vector) {
    float[] result = new float[vector.length];
    for (int i = 0; i < vector.length; i++) {
      result[i] = (float) (1.0f / (1.0f + FastMath.exp(-vector[i])));
    }
    return result;
  }

  @Override
  public float max(float[] vector) {
    float max = Float.NEGATIVE_INFINITY;
    for (float f : vector) {
      max = FastMath.max(max, f);
    }
    return max;
  }

  @Override
  public float[] weightedMean(float[][] matrix, float[] weights) {
    final int rows = matrix.length;
    int cols = matrix[0].length;

    float[] meanVector = new float[cols];
    float weightSum = 0;

    for (int row = 0; row < rows; row++) {
      weightSum += weights[row];
      for (int col = 0; col < cols; col++) {
        meanVector[col] += matrix[row][col] * weights[row];
      }
    }
    for (int j = 0; j < cols; j++) {
      meanVector[j] /= weightSum;
    }
    return meanVector;
  }
}
