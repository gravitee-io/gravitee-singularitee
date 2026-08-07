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

import static ai.onnxruntime.OrtEnvironment.getAvailableProviders;
import static java.lang.System.getProperty;
import static java.util.Objects.requireNonNull;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import io.gravitee.singularitee.inference.api.InferenceModel;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class OnnxInference<C extends OnnxConfig<?>, I, O> extends InferenceModel<C, I, O> {

  private static final String INTRA_OPS_THREADS_KEY = "GRAVITEE_ONNX_INTRA_OPS_NUM_THREADS";
  private static final List<String> POSSIBLE_KEYS = List.of(
    INTRA_OPS_THREADS_KEY,
    INTRA_OPS_THREADS_KEY.toLowerCase()
  );

  private static final int DEFAULT_MAX_INTRA_OPS_THREADS =
    Runtime.getRuntime().availableProcessors();
  private static final Pattern NUMBER_PATTERN = Pattern.compile("^[0-9]+$");
  private static final int MAX_INTRA_OPS_THREADS = getMaxIntraOpsThreads();

  private static int getMaxIntraOpsThreads() {
    return POSSIBLE_KEYS.stream()
      .map(System::getenv)
      .filter(Objects::nonNull)
      .filter(value -> NUMBER_PATTERN.matcher(value).matches())
      .map(Integer::valueOf)
      .findFirst()
      .orElse(DEFAULT_MAX_INTRA_OPS_THREADS);
  }

  protected final OrtEnvironment environment;
  protected final OrtSession session;

  protected OnnxInference(C config) {
    super(config);
    this.environment = OrtEnvironment.getEnvironment();
    this.session = getSession();
  }

  private OrtSession getSession() {
    try {
      OrtSession.SessionOptions options = new SessionOptions();
      if (getAvailableProviders().contains(OrtProvider.CUDA)) {
        options.addCUDA();
      }

      options.setIntraOpNumThreads(MAX_INTRA_OPS_THREADS);
      final String modelPath = config.getResource().getModel().toAbsolutePath().toString();
      return environment.createSession(modelPath, options);
    } catch (OrtException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public void close() {
    try {
      this.session.close();
      this.environment.close();
    } catch (OrtException e) {
      throw new RuntimeException(e);
    }
  }
}
