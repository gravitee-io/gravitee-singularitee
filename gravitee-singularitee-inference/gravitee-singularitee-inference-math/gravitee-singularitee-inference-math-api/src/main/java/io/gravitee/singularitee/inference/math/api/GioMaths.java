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
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface GioMaths {
  default float cosineScore(float[] v1, float[] v2) {
    return (1f + cosineSimilarity(v1, v2)) / 2;
  }

  float cosineSimilarity(float[] v1, float[] v2);

  float euclideanDistance(float[] v1, float[] v2);

  float[] softmax(float[] vector);

  float[] sigmoid(float[] vector);

  float[] mean(float[][] vectors);

  float[] weightedMean(float[][] vector, float[] weights);

  float max(float[] vector);

  float[] normalize(float[] vector);

  float normL2(float[] vector);
}
