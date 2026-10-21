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
package io.gravitee.singularitee.grpc.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The chunk retry: how often it retries, and that a failure racing a cancel stays quiet. */
class HuggingFaceModelDownloaderRetryTest {

  @Test
  void a_failing_attempt_is_retried_then_its_error_passes_through() {
    var attempts = new AtomicInteger();

    assertThatThrownBy(() ->
      HuggingFaceModelDownloader.retried(
        () -> {
          attempts.incrementAndGet();
          return Completable.error(new IllegalStateException("short chunk"));
        },
        2
      ).blockingAwait()
    ).hasMessage("short chunk");
    assertThat(attempts).hasValue(3);
  }

  @Test
  void a_final_failure_racing_the_cancel_is_never_reported_as_uncaught() throws Exception {
    assertThat(
      uncaughtWhenRacing(source -> HuggingFaceModelDownloader.retried(() -> source, 0))
    ).isEmpty();
  }

  /**
   * Fails each source on one thread while another cancels the retrying chain, many times over,
   * and returns what reached {@code RxJavaPlugins.onError}.
   */
  private static List<Throwable> uncaughtWhenRacing(Function<Completable, Completable> retrying)
    throws Exception {
    List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    var previous = RxJavaPlugins.getErrorHandler();
    RxJavaPlugins.setErrorHandler(uncaught::add);
    try (var threads = Executors.newFixedThreadPool(2)) {
      for (int i = 0; i < 20_000; i++) {
        var source = CompletableSubject.create();
        Disposable chain = retrying.apply(source).subscribe(() -> {}, e -> {});
        var start = new CountDownLatch(1);
        var fail = threads.submit(() -> {
          start.await();
          source.onError(new IllegalStateException("final failure"));
          return null;
        });
        var cancel = threads.submit(() -> {
          start.await();
          chain.dispose();
          return null;
        });
        start.countDown();
        fail.get(5, TimeUnit.SECONDS);
        cancel.get(5, TimeUnit.SECONDS);
      }
    } finally {
      RxJavaPlugins.setErrorHandler(previous);
    }
    return uncaught;
  }
}
