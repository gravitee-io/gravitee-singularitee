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
package io.gravitee.singularitee.engine.remote;

import io.gravitee.singularitee.client.SingulariteeClient;
import io.gravitee.singularitee.engine.api.DecisionEngine;
import io.gravitee.singularitee.engine.api.decision.DecisionMessages;
import io.gravitee.singularitee.inference.api.decision.DecisionCapabilities;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.protocol.DecisionInfo;
import io.reactivex.rxjava3.core.Single;
import io.vertx.grpc.client.InvalidStatusException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A decision model served by another Singularitee: calls travel over gRPC {@code Decide}, the
 * answers come back typed. The remote validates the questions against its model; its refusals
 * come back as the matching {@link DecisionException}.
 *
 * <p>{@link #capabilities()} reports what the remote's {@code GetModel} said; until that answers
 * it reports a permissive placeholder ({@code custom}, both input modes), and the remote remains
 * the one that refuses what its model cannot take.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RemoteDecisionEngine implements DecisionEngine {

  private static final Logger LOGGER = LoggerFactory.getLogger(RemoteDecisionEngine.class);

  private static final DecisionCapabilities UNKNOWN = new DecisionCapabilities(
    DecisionFamily.CUSTOM,
    255,
    true,
    true
  );

  private final SingulariteeClient client;
  private final String modelId;
  private final AtomicBoolean probing = new AtomicBoolean();
  private volatile DecisionCapabilities capabilities;

  /** Creates a proxy for {@code modelId} on the server behind {@code client}. */
  public RemoteDecisionEngine(SingulariteeClient client, String modelId) {
    this(client, modelId, null);
  }

  /**
   * Creates a proxy whose capabilities are already known from a {@code GetModel} the caller
   * made; {@code null} probes lazily.
   */
  public RemoteDecisionEngine(SingulariteeClient client, String modelId, DecisionInfo info) {
    this.client = client;
    this.modelId = modelId;
    this.capabilities = info == null || info.getFamily().isEmpty() ? null : capabilities(info);
  }

  @Override
  public DecisionCapabilities capabilities() {
    DecisionCapabilities known = capabilities;
    if (known != null) return known;
    probe();
    return UNKNOWN;
  }

  @Override
  public Single<DecisionResult> rxDecide(DecisionRequest request) {
    return client
      .decide(DecisionMessages.request(modelId, request))
      .map(DecisionMessages::result)
      .onErrorResumeNext(e -> Single.error(domainError(modelId, e)));
  }

  /**
   * The decision failure behind a remote's gRPC status, so a request the remote refuses stays
   * the caller's fault here too. The client receives no status message, only the code.
   */
  static Throwable domainError(String modelId, Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof InvalidStatusException status) {
        String remote = "the remote model '" + modelId + "' ";
        return switch (status.actualStatus()) {
          case INVALID_ARGUMENT -> new DecisionException(
            Reason.INVALID_REQUEST,
            remote + "refused the request as invalid",
            error
          );
          case FAILED_PRECONDITION -> new DecisionException(
            Reason.UNSUPPORTED_INPUT,
            remote + "does not take this input",
            error
          );
          // The model exists here: a remote without it is a deployment fault, not the caller's.
          default -> new DecisionException(
            Reason.EVALUATION_FAILED,
            remote + "failed with " + status.actualStatus(),
            error
          );
        };
      }
    }
    return error;
  }

  private void probe() {
    if (!probing.compareAndSet(false, true)) return;
    client
      .getModel(modelId)
      .doFinally(() -> probing.set(false))
      .subscribe(
        response -> {
          if (response.hasDecision()) capabilities = capabilities(response.getDecision());
        },
        e ->
          LOGGER.debug(
            "RemoteDecisionEngine: GetModel for '{}' failed: {}",
            modelId,
            e.getMessage()
          )
      );
  }

  private static DecisionCapabilities capabilities(DecisionInfo info) {
    DecisionFamily family;
    try {
      family = DecisionFamily.valueOf(info.getFamily().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      family = DecisionFamily.CUSTOM;
    }
    return new DecisionCapabilities(
      family,
      info.getMaxOptions(),
      info.getTemplated(),
      info.getRaw()
    );
  }

  @Override
  public void close() {
    // Nothing to close: the client is shared
  }
}
