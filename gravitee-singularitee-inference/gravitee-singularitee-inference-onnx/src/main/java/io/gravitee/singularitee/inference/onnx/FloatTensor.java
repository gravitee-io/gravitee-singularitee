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
package io.gravitee.singularitee.inference.onnx;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtException;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * A flat, row-major float tensor: one contiguous {@link FloatBuffer} plus its shape.
 *
 * <p>Replaces nested {@code float[][]...} reads of ONNX outputs on the inference hot path:
 * {@link #of(OnnxValue)} is a single bulk copy into a direct buffer, versus
 * {@code OnnxValue.getValue()}'s reflective element-by-element materialization
 * ({@code OrtUtil.fillArrayFromBuffer} + {@code Array.get} boxing). Consumers then copy out
 * only the rows they actually use (skipping padding), instead of materializing the whole
 * padded batch.
 *
 * <p>Accessors use absolute indexing; the buffer's position/limit are not part of the
 * contract. Not thread-safe for writes; concurrent absolute reads are safe.
 *
 * @param data  the tensor elements, row-major, starting at index 0
 * @param shape the tensor dimensions
 */
public record FloatTensor(FloatBuffer data, long[] shape) {
  /** Reads an ONNX output tensor into a flat {@code FloatTensor} with one bulk copy. */
  public static FloatTensor of(OnnxValue value) throws OrtException {
    var tensor = (OnnxTensor) value;
    var info = tensor.getInfo();
    if (info.type == OnnxJavaType.FLOAT) {
      // Direct-buffer copy: keeps the data off-heap (getFloatBuffer() would allocate a
      // heap FloatBuffer, churning the young generation for multi-MB tensors).
      var buf = tensor.getByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer();
      return new FloatTensor(buf, info.getShape());
    }
    // Non-FLOAT element types (e.g. FLOAT16 outputs): getFloatBuffer() converts.
    return new FloatTensor(tensor.getFloatBuffer(), info.getShape());
  }

  /** Size of dimension {@code i}. */
  public int dim(int i) {
    return (int) shape[i];
  }

  /** Number of elements in one step of dimension {@code i} (product of trailing dims). */
  public long stride(int i) {
    long s = 1;
    for (int d = i + 1; d < shape.length; d++) {
      s *= shape[d];
    }
    return s;
  }

  /** Copies {@code length} elements starting at flat offset {@code srcOffset} into a new array. */
  public float[] row(long srcOffset, int length) {
    var out = new float[length];
    data.get((int) srcOffset, out, 0, length);
    return out;
  }

  /**
   * Materializes rows {@code [0, rowCount)} of a rank-2 view starting at {@code base} with
   * {@code rowLength} elements per row — e.g. one batch entry's unpadded sequence rows.
   */
  public float[][] rows(long base, int rowCount, int rowLength) {
    var out = new float[rowCount][];
    for (int i = 0; i < rowCount; i++) {
      out[i] = row(base + (long) i * rowLength, rowLength);
    }
    return out;
  }
}
