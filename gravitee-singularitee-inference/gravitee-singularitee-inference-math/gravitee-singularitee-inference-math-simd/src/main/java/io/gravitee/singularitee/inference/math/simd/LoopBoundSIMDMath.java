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
package io.gravitee.singularitee.inference.math.simd;

import static jdk.incubator.vector.VectorOperators.ADD;

import io.gravitee.singularitee.inference.math.api.GioMaths;
import io.gravitee.singularitee.inference.math.vanilla.NativeMath;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.apache.commons.math3.util.FastMath;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class LoopBoundSIMDMath implements GioMaths {

  private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

  private LoopBoundSIMDMath() {}

  public static final GioMaths INSTANCE = new LoopBoundSIMDMath();

  @Override
  public float euclideanDistance(float[] v1, float[] v2) {
    if (v1.length != v2.length) {
      throw new IllegalArgumentException("Both vectors must have the same dimension.");
    }

    int length = v1.length;
    int i = 0;

    FloatVector sumVector = FloatVector.zero(SPECIES);
    for (; i < SPECIES.loopBound(length); i += SPECIES.length()) {
      FloatVector vec1 = FloatVector.fromArray(SPECIES, v1, i);
      FloatVector vec2 = FloatVector.fromArray(SPECIES, v2, i);
      FloatVector diff = vec1.sub(vec2);
      sumVector = sumVector.add(diff.mul(diff));
    }

    float sum = sumVector.reduceLanes(ADD);
    for (; i < length; i++) {
      float diff = v1[i] - v2[i];
      sum += diff * diff;
    }

    return (float) FastMath.sqrt(sum);
  }

  @Override
  public float cosineSimilarity(float[] v1, float[] v2) {
    int length = v1.length;
    FloatVector dotProductVector = FloatVector.zero(SPECIES);
    FloatVector magnitudeAVector = FloatVector.zero(SPECIES);
    FloatVector magnitudeBVector = FloatVector.zero(SPECIES);

    int i = 0;
    for (; i < SPECIES.loopBound(length); i += SPECIES.length()) {
      FloatVector vecA = FloatVector.fromArray(SPECIES, v1, i);
      FloatVector vecB = FloatVector.fromArray(SPECIES, v2, i);

      dotProductVector = dotProductVector.add(vecA.mul(vecB));
      magnitudeAVector = magnitudeAVector.add(vecA.mul(vecA));
      magnitudeBVector = magnitudeBVector.add(vecB.mul(vecB));
    }

    float dotProduct = dotProductVector.reduceLanes(ADD);
    float magnitudeA = magnitudeAVector.reduceLanes(ADD);
    float magnitudeB = magnitudeBVector.reduceLanes(ADD);

    for (; i < length; i++) {
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
  public float[] mean(float[][] matrix) {
    int rows = matrix.length;
    int cols = matrix[0].length;
    float[] accumulator = new float[cols];

    for (float[] row : matrix) {
      int j = 0;
      for (; j < SPECIES.loopBound(cols); j += SPECIES.length()) {
        var v1 = FloatVector.fromArray(SPECIES, accumulator, j);
        var v2 = FloatVector.fromArray(SPECIES, row, j);
        v1.add(v2).intoArray(accumulator, j);
      }
      for (; j < cols; j++) {
        accumulator[j] += row[j];
      }
    }

    return computeMean(rows, accumulator, accumulator);
  }

  @Override
  public float[] normalize(float[] vector) {
    return computeMean(normL2(vector), vector, new float[vector.length]);
  }

  @Override
  public float normL2(float[] vector) {
    return NativeMath.INSTANCE.normL2(vector);
  }

  @Override
  public float[] weightedMean(float[][] matrix, float[] weights) {
    int rows = matrix.length;
    int cols = matrix[0].length;
    float[] accumulator = new float[cols];
    float totalWeight = 0.0f;

    for (int i = 0; i < rows; i++) {
      totalWeight += weights[i];
      int j = 0;
      for (; j < SPECIES.loopBound(cols); j += SPECIES.length()) {
        var v1 = FloatVector.fromArray(SPECIES, accumulator, j);
        var v2 = FloatVector.fromArray(SPECIES, matrix[i], j);
        v1.add(v2.mul(weights[i])).intoArray(accumulator, j);
      }
      for (; j < cols; j++) {
        accumulator[j] += matrix[i][j] * weights[i];
      }
    }

    return computeMean(totalWeight, accumulator);
  }

  @Override
  public float[] softmax(float[] vector) {
    var maxLogit = max(vector);
    FloatVector sumVector = FloatVector.zero(SPECIES);
    float[] expVector = new float[vector.length];

    int i = 0;
    for (; i < SPECIES.loopBound(vector.length); i += SPECIES.length()) {
      var v = FloatVector.fromArray(SPECIES, vector, i);
      var adjustedV = v.sub(maxLogit);
      var expV = adjustedV.lanewise(VectorOperators.EXP);

      expV.intoArray(expVector, i);
      sumVector = sumVector.add(expV);
    }

    float sum = sumVector.reduceLanes(VectorOperators.ADD);
    for (; i < vector.length; i++) {
      expVector[i] = (float) FastMath.exp(vector[i] - maxLogit);
      sum += expVector[i];
    }

    return computeMean(sum, expVector);
  }

  @Override
  public float[] sigmoid(float[] vector) {
    float[] result = new float[vector.length];
    var one = FloatVector.broadcast(SPECIES, 1.0f);

    int i = 0;
    for (; i < SPECIES.loopBound(vector.length); i += SPECIES.length()) {
      var v = FloatVector.fromArray(SPECIES, vector, i);
      // 1 / (1 + exp(-x)) — the -x form stays finite for large +x (exp(-x) → 0).
      var negExp = v.mul(-1.0f).lanewise(VectorOperators.EXP);
      one.div(one.add(negExp)).intoArray(result, i);
    }
    for (; i < vector.length; i++) {
      result[i] = (float) (1.0f / (1.0f + FastMath.exp(-vector[i])));
    }
    return result;
  }

  @Override
  public float max(float[] vector) {
    var maxVector = FloatVector.broadcast(SPECIES, Float.NEGATIVE_INFINITY);

    int i = 0;
    for (; i < SPECIES.loopBound(vector.length); i += SPECIES.length()) {
      var v = FloatVector.fromArray(SPECIES, vector, i);
      maxVector = maxVector.max(v);
    }

    float max = maxVector.reduceLanes(VectorOperators.MAX);
    for (; i < vector.length; i++) {
      max = FastMath.max(max, vector[i]);
    }

    return max;
  }

  private static float[] computeMean(float sum, float[] vector) {
    return computeMean(sum, vector, vector);
  }

  private static float[] computeMean(float sum, float[] vector, float[] accumulator) {
    int i = 0;
    for (; i < SPECIES.loopBound(accumulator.length); i += SPECIES.length()) {
      var v1 = FloatVector.fromArray(SPECIES, vector, i);
      v1.div(sum).intoArray(accumulator, i);
    }
    for (; i < accumulator.length; i++) {
      accumulator[i] = vector[i] / sum;
    }
    return accumulator;
  }
}
