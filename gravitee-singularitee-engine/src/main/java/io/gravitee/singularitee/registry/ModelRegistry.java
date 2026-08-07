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
package io.gravitee.singularitee.registry;

import io.gravitee.singularitee.engine.*;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe in-memory registry of published {@link ModelEngine} instances.
 *
 * <p>Models are loaded once at startup from the workspace YAML and live for the
 * lifetime of the process. The registry only supports {@code register}, {@code get}
 * / {@code rxGet}, and bulk cleanup via {@code shutdown()}. There is no runtime
 * model lifecycle (no draining, no swapping, no per-model retirement) — the
 * deployment is static.
 *
 * <p>Sequence IDs are issued per-model via an {@link AtomicInteger} counter so that
 * callers can safely multiplex concurrent inference requests without collisions.
 *
 * <p><strong>No {@code gravitee-inference-api} types are used here.</strong>
 * The registry only knows about {@link ModelEngine} and its local subtypes.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ModelRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(ModelRegistry.class);

  private final ConcurrentHashMap<String, ModelEntry> models = new ConcurrentHashMap<>();

  // ---------------------------------------------------------------------------
  // Register
  // ---------------------------------------------------------------------------

  /**
   * Registers a model engine and starts it if it is a text-gen engine.
   *
   * <p>If the caller supplies a non-blank {@code modelId} and that ID is already
   * in use, an {@link IllegalArgumentException} is thrown — the existing model is
   * left untouched.  Pass an empty string to have the server assign a UUID.
   *
   * @param modelId       caller-supplied ID, or empty to auto-generate a UUID
   * @param modelName     human-readable model name (for logging)
   * @param engine        the model engine, not yet started
   * @param tokenConsumer callback that receives every token emitted by a text-gen engine;
   *                      ignored for classifier and embedding engines
   * @return the final model_id (either the caller's value or the generated UUID)
   * @throws IllegalArgumentException if the supplied modelId is already registered
   */
  public String register(
    String modelId,
    String modelName,
    ModelEngine engine,
    java.util.function.Consumer<ModelEngineToken> tokenConsumer
  ) {
    String resolvedId = (modelId != null && !modelId.isBlank())
      ? modelId
      : java.util.UUID.randomUUID().toString();

    if (
      models.putIfAbsent(resolvedId, new ModelEntry(modelName, engine, new AtomicInteger(0))) !=
      null
    ) {
      throw new IllegalArgumentException("model_id already in use: " + resolvedId);
    }

    // Start text-gen engines after the ID is confirmed to avoid orphaned
    // engine threads on conflict.
    if (engine instanceof TextGenEngine tge) {
      tge.start(tokenConsumer);
    }

    LOGGER.info("Model published: id={}, name={}, type={}", resolvedId, modelName, engine.type());
    return resolvedId;
  }

  // ---------------------------------------------------------------------------
  // Get
  // ---------------------------------------------------------------------------

  /**
   * Reactively looks up a published model by its identifier.
   *
   * <p>Emits the {@link ModelEntry} if found, or completes empty if the model has
   * not been published. All models are registered at startup and remain active for
   * the lifetime of the process, so no draining/waiting is ever required.
   *
   * @param modelId the model identifier returned at registration
   * @return a {@link Maybe} emitting the entry, or empty if not found
   */
  public Maybe<ModelEntry> rxGet(String modelId) {
    ModelEntry entry = models.get(modelId);
    if (entry == null) {
      LOGGER.debug("rxGet('{}'): not found in registry (size={})", modelId, models.size());
      return Maybe.empty();
    }
    return Maybe.just(entry);
  }

  /**
   * Looks up a published model by its identifier.
   *
   * @param modelId the model identifier returned at registration
   * @return the entry, or empty if the model has not been published
   */
  public Optional<ModelEntry> get(String modelId) {
    return Optional.ofNullable(models.get(modelId));
  }

  // ---------------------------------------------------------------------------
  // List
  // ---------------------------------------------------------------------------

  /**
   * Returns a snapshot of all currently registered model entries as an
   * immutable set of (modelId, ModelEntry) pairs.
   *
   * @return the registered models; never {@code null}
   */
  public java.util.Set<java.util.Map.Entry<String, ModelEntry>> entries() {
    return java.util.Set.copyOf(models.entrySet());
  }

  // ---------------------------------------------------------------------------
  // Shutdown
  // ---------------------------------------------------------------------------

  /**
   * Closes every registered engine and clears the registry. Called once on
   * process shutdown to release native resources (GPU memory, file handles,
   * threads).
   */
  public void shutdown() {
    for (var entry : models.entrySet()) {
      String id = entry.getKey();
      ModelEntry me = entry.getValue();
      try {
        me.engine().close();
        LOGGER.info("Model engine closed: id={}, name={}", id, me.modelName());
      } catch (Exception e) {
        LOGGER.error("Error closing engine for model {}: {}", id, e.getMessage());
      }
    }
    models.clear();
  }

  // ---------------------------------------------------------------------------
  // ModelEntry record
  // ---------------------------------------------------------------------------

  /**
   * An entry in the model registry.
   *
   * @param modelName     human-readable name
   * @param engine        the running model engine
   * @param seqCounter    monotonically-increasing sequence-ID generator
   */
  public record ModelEntry(String modelName, ModelEngine engine, AtomicInteger seqCounter) {
    /** Returns the in-flight sequence counter (shared reference). */
    public AtomicInteger inFlightCount() {
      return seqCounter;
    }

    /**
     * Atomically increments and returns the next sequence ID, wrapping back to 1 at
     * Integer.MAX_VALUE — a plain incrementAndGet would go negative on overflow and
     * fail InferenceToken's seqId >= 0 validation on every request until restart.
     * Collision after a wrap would require a sequence to stay in-flight across ~2^31
     * requests, which slot-bounded concurrency makes impossible.
     */
    public int nextSequenceId() {
      return seqCounter.updateAndGet(v -> v >= Integer.MAX_VALUE - 1 ? 1 : v + 1);
    }
  }
}
