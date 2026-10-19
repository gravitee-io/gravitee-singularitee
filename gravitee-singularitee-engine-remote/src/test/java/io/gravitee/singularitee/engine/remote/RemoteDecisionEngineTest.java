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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.client.SingulariteeClient;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.reactivex.rxjava3.core.Single;
import io.vertx.grpc.client.InvalidStatusException;
import io.vertx.grpc.common.GrpcStatus;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** A remote's refusal keeps its blame on this side of the proxy. */
class RemoteDecisionEngineTest {

  private static final DecisionRequest REQUEST = new DecisionRequest(
    new DecisionInput.Templated("text", List.of(new Question.Noul("q", "?", null, null))),
    false
  );

  @Test
  void a_remote_status_becomes_the_matching_decision_failure() {
    var expected = Map.of(
      GrpcStatus.INVALID_ARGUMENT,
      Reason.INVALID_REQUEST,
      GrpcStatus.FAILED_PRECONDITION,
      Reason.UNSUPPORTED_INPUT,
      GrpcStatus.NOT_FOUND,
      Reason.EVALUATION_FAILED,
      GrpcStatus.INTERNAL,
      Reason.EVALUATION_FAILED
    );
    expected.forEach((status, reason) -> {
      var client = mock(SingulariteeClient.class);
      var failure = new CompletionException(new InvalidStatusException(GrpcStatus.OK, status));
      when(client.decide(any())).thenReturn(Single.error(failure));
      var engine = new RemoteDecisionEngine(client, "judge");

      assertThatThrownBy(() -> engine.rxDecide(REQUEST).blockingGet())
        .as("%s", status)
        .isInstanceOf(DecisionException.class)
        .hasMessageContaining("'judge'")
        .extracting(e -> ((DecisionException) e).reason())
        .isEqualTo(reason);
    });
  }

  @Test
  void a_transport_failure_passes_through() {
    var client = mock(SingulariteeClient.class);
    when(client.decide(any())).thenReturn(Single.error(new IllegalStateException("closed")));
    var engine = new RemoteDecisionEngine(client, "judge");

    assertThatThrownBy(() -> engine.rxDecide(REQUEST).blockingGet())
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("closed");
  }
}
