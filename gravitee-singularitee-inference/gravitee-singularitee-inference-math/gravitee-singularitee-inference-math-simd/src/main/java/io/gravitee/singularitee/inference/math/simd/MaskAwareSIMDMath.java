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
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.apache.commons.math3.util.FastMath;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class MaskAwareSIMDMath implements GioMaths {

  private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

  private MaskAwareSIMDMath() {}

  public static final GioMaths INSTANCE = new MaskAwareSIMDMath();

  @Override
  public float cosineSimilarity(float[] v1, float[] v2) {
    if (v1.length != v2.length) {
      throw new IllegalArgumentException("Both vectors must have the same dimension.");
    }

    int length = v1.length;
    var dotProductVector = FloatVector.zero(SPECIES);
    var magnitudeAVector = FloatVector.zero(SPECIES);
    var magnitudeBVector = FloatVector.zero(SPECIES);

    for (int i = 0; i < length; i += SPECIES.length()) {
      var mask = SPECIES.indexInRange(i, length);
      var vec1 = FloatVector.fromArray(SPECIES, v1, i, mask);
      var vec2 = FloatVector.fromArray(SPECIES, v2, i, mask);

      dotProductVector = dotProductVector.add(vec1.mul(vec2));
      magnitudeAVector = magnitudeAVector.add(vec1.mul(vec1));
      magnitudeBVector = magnitudeBVector.add(vec2.mul(vec2));
    }

    float dotProduct = dotProductVector.reduceLanes(ADD);
    float magnitudeA = magnitudeAVector.reduceLanes(ADD);
    float magnitudeB = magnitudeBVector.reduceLanes(ADD);

    if (magnitudeA == 0 || magnitudeB == 0) {
      return 0.0f;
    }

    return (float) (dotProduct / (FastMath.sqrt(magnitudeA) * FastMath.sqrt(magnitudeB)));
  }

  @Override
  public float euclideanDistance(float[] v1, float[] v2) {
    if (v1.length != v2.length) {
      throw new IllegalArgumentException("Both vectors must have the same dimension.");
    }

    FloatVector result = FloatVector.zero(SPECIES);
    int length = v1.length;
    for (int i = 0; i < length; i += SPECIES.length()) {
      var mask = SPECIES.indexInRange(i, length);

      var vec1 = FloatVector.fromArray(SPECIES, v1, i, mask);
      var vec2 = FloatVector.fromArray(SPECIES, v2, i, mask);
      var diff = vec1.sub(vec2);

      result = result.add(diff.mul(diff), mask);
    }

    return (float) FastMath.sqrt(result.reduceLanes(ADD));
  }

  @Override
  public float[] mean(float[][] matrix) {
    int rows = matrix.length;
    int cols = matrix[0].length;
    float[] accumulator = new float[cols];

    for (float[] row : matrix) {
      for (int j = 0; j < cols; j += SPECIES.length()) {
        var mask = SPECIES.indexInRange(j, cols);
        var v1 = FloatVector.fromArray(SPECIES, accumulator, j, mask);
        var v2 = FloatVector.fromArray(SPECIES, row, j, mask);
        v1.add(v2).intoArray(accumulator, j, mask);
      }
    }

    return computeMean(rows, accumulator);
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
  public float[] softmax(float[] vector) {
    var maxLogit = max(vector);
    FloatVector sumVector = FloatVector.zero(SPECIES);
    float[] expVector = new float[vector.length];

    for (int i = 0; i < vector.length; i += SPECIES.length()) {
      var mask = SPECIES.indexInRange(i, vector.length);
      var v = FloatVector.fromArray(SPECIES, vector, i, mask);
      var adjustedV = v.sub(maxLogit, mask);
      var expV = adjustedV.lanewise(VectorOperators.EXP, mask);

      expV.intoArray(expVector, i, mask);
      sumVector = sumVector.add(expV, mask);
    }

    return computeMean(sumVector.reduceLanes(VectorOperators.ADD), expVector);
  }

  @Override
  public float[] sigmoid(float[] vector) {
    float[] result = new float[vector.length];
    var one = FloatVector.broadcast(SPECIES, 1.0f);

    for (int i = 0; i < vector.length; i += SPECIES.length()) {
      var mask = SPECIES.indexInRange(i, vector.length);
      var v = FloatVector.fromArray(SPECIES, vector, i, mask);
      // 1 / (1 + exp(-x)) — the -x form stays finite for large +x (exp(-x) → 0).
      var negExp = v.mul(-1.0f).lanewise(VectorOperators.EXP, mask);
      one.div(one.add(negExp)).intoArray(result, i, mask);
    }
    return result;
  }

  @Override
  public float max(float[] vector) {
    var maxVector = FloatVector.broadcast(SPECIES, Float.NEGATIVE_INFINITY);

    for (int i = 0; i < vector.length; i += SPECIES.length()) {
      var mask = SPECIES.indexInRange(i, vector.length);
      var v = FloatVector.fromArray(SPECIES, vector, i, mask);
      maxVector = maxVector.max(v);
    }

    return maxVector.reduceLanes(VectorOperators.MAX);
  }

  @Override
  public float[] weightedMean(float[][] matrix, float[] weights) {
    int rows = matrix.length;
    int cols = matrix[0].length;
    float[] accumulator = new float[cols];
    float totalWeight = 0.0f;

    for (int i = 0; i < rows; i++) {
      totalWeight += weights[i];
      for (int j = 0; j < cols; j += SPECIES.length()) {
        var mask = SPECIES.indexInRange(j, cols);
        var v1 = FloatVector.fromArray(SPECIES, accumulator, j, mask);
        var v2 = FloatVector.fromArray(SPECIES, matrix[i], j, mask);
        v1.add(v2.mul(weights[i])).intoArray(accumulator, j, mask);
      }
    }

    return computeMean(totalWeight, accumulator);
  }

  private static float[] computeMean(float total, float[] vector) {
    return computeMean(total, vector, vector);
  }

  private static float[] computeMean(float total, float[] vector, float[] accumulator) {
    for (int i = 0; i < accumulator.length; i += SPECIES.length()) {
      var mask = SPECIES.indexInRange(i, accumulator.length);
      var v1 = FloatVector.fromArray(SPECIES, vector, i, mask);
      v1.div(total).intoArray(accumulator, i, mask);
    }
    return accumulator;
  }
}
