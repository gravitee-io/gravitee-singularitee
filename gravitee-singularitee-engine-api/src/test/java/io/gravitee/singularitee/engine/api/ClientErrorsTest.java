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
package io.gravitee.singularitee.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.reactivex.rxjava3.exceptions.MissingBackpressureException;
import io.vertx.core.Future;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ClientErrorsTest {

  @Test
  void a_message_written_for_the_caller_is_kept() {
    var notFound = Future.failedFuture("Model not found: llm").cause();
    assertThat(ClientErrors.message(notFound, "Internal error")).isEqualTo("Model not found: llm");
    assertThat(ClientErrors.message(new IllegalArgumentException("bad dims"), "x")).isEqualTo(
      "bad dims"
    );
    assertThat(ClientErrors.message(new MissingBackpressureException("full"), "x")).isEqualTo(
      "full"
    );
  }

  @Test
  void an_internal_error_is_replaced_by_a_reference() {
    var leak = new IOException("cannot open /home/svc/.cache/models/secret/model.gguf");

    String message = ClientErrors.message(leak, "Inference failed");

    assertThat(message).startsWith("Inference failed (ref ").doesNotContain("/home/svc");
  }

  @Test
  void for_client_wraps_once_and_keeps_client_facing_errors() {
    var caller = new IllegalArgumentException("bad request");
    assertThat(ClientErrors.forClient(caller, "x")).isSameAs(caller);

    var wrapped = ClientErrors.forClient(
      new IllegalStateException("engine at 10.0.0.4 died"),
      "Internal error"
    );
    assertThat(wrapped.getMessage()).startsWith("Internal error (ref ").doesNotContain("10.0.0.4");
    assertThat(ClientErrors.forClient(wrapped, "x")).isSameAs(wrapped);
    assertThat(ClientErrors.message(wrapped, "x")).isEqualTo(wrapped.getMessage());
  }
}
