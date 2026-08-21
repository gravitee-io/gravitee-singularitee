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
package io.gravitee.singularitee.inference.math.api;

/**
 * Float vector kernels used by the inference engines: similarity, pooling and activations.
 *
 * Implementations are stateless and safe to share across threads. Inputs are never mutated;
 * every array-returning method allocates its result. Vectors passed to a binary operation must
 * have the same length.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface GioMaths {
  /** Cosine similarity rescaled from {@code [-1, 1]} to {@code [0, 1]}. */
  default float cosineScore(float[] v1, float[] v2) {
    return (1f + cosineSimilarity(v1, v2)) / 2;
  }

  /** Cosine similarity in {@code [-1, 1]}; {@code 0} when either vector has zero magnitude. */
  float cosineSimilarity(float[] v1, float[] v2);

  /** L2 distance between two vectors of equal length. */
  float euclideanDistance(float[] v1, float[] v2);

  /** Numerically stable softmax (max-subtracted) over the whole vector. */
  float[] softmax(float[] vector);

  /** Element-wise logistic sigmoid. */
  float[] sigmoid(float[] vector);

  /** Column-wise mean of a non-empty row-major matrix. */
  float[] mean(float[][] vectors);

  /** Column-wise mean of the rows, each row scaled by its weight, divided by the weight sum. */
  float[] weightedMean(float[][] vector, float[] weights);

  /** Largest element; {@code -Infinity} for an empty vector. */
  float max(float[] vector);

  /** The vector divided by its L2 norm. */
  float[] normalize(float[] vector);

  /** Euclidean (L2) norm. */
  float normL2(float[] vector);
}
