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
package io.gravitee.singularitee.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vertx.core.Future;
import io.vertx.grpc.client.InvalidStatusException;
import io.vertx.grpc.common.GrpcStatus;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A retried unary call sends its RPC again, and only a transient failure is retried. */
class SingulariteeClientRetryTest {

  @Test
  void a_retry_sends_the_rpc_again() {
    var calls = new AtomicInteger();

    String answer = SingulariteeClient.unaryCall("server:9090", () ->
      calls.incrementAndGet() < 3
        ? Future.<String>failedFuture(new ConnectException("refused"))
        : Future.succeededFuture("ok")
    ).blockingGet();

    assertThat(answer).isEqualTo("ok");
    assertThat(calls).hasValue(3);
  }

  @Test
  void a_server_still_loading_is_retried() {
    var calls = new AtomicInteger();

    String answer = SingulariteeClient.unaryCall("server:9090", () ->
      calls.incrementAndGet() == 1
        ? Future.<String>failedFuture(
          new InvalidStatusException(GrpcStatus.OK, GrpcStatus.UNAVAILABLE)
        )
        : Future.succeededFuture("ok")
    ).blockingGet();

    assertThat(answer).isEqualTo("ok");
    assertThat(calls).hasValue(2);
  }

  @Test
  void a_request_error_fails_at_once() {
    var calls = new AtomicInteger();

    assertThatThrownBy(() ->
      SingulariteeClient.unaryCall("server:9090", () -> {
        calls.incrementAndGet();
        return Future.<String>failedFuture(
          new InvalidStatusException(GrpcStatus.OK, GrpcStatus.INVALID_ARGUMENT)
        );
      }).blockingGet()
    ).isInstanceOf(InvalidStatusException.class);
    assertThat(calls).hasValue(1);
  }

  @Test
  void a_server_that_never_answers_times_out_with_its_address() {
    assertThatThrownBy(() ->
      SingulariteeClient.unaryCall(
        "server:9090",
        () -> Future.<String>failedFuture(new ConnectException("refused")),
        300
      ).blockingGet()
    )
      .hasCauseInstanceOf(TimeoutException.class)
      .hasMessageContaining("no answer from server:9090 within 300 ms");
  }

  @Test
  void nothing_is_sent_before_subscription() {
    var calls = new AtomicInteger();

    var single = SingulariteeClient.unaryCall("server:9090", () -> {
      calls.incrementAndGet();
      return Future.succeededFuture("ok");
    });

    assertThat(calls).hasValue(0);
    single.blockingGet();
    assertThat(calls).hasValue(1);
  }
}
