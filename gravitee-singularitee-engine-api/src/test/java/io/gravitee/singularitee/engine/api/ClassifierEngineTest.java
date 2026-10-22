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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How the two batch overloads reach an engine's own batching. */
class ClassifierEngineTest {

  private final ClassifierEngine engine = mock(
    ClassifierEngine.class,
    withSettings().defaultAnswer(CALLS_REAL_METHODS)
  );

  @Test
  void a_batch_without_labels_reaches_the_overload_engines_batch_on() {
    var requests = List.of(new ClassifyRequest("a"), new ClassifyRequest("b"));
    var batched = List.of(response("a"), response("b"));
    doReturn(Single.just(batched)).when(engine).rxClassifyBatch(eq(requests), anyList());

    assertThat(engine.rxClassifyBatch(requests).blockingGet()).isEqualTo(batched);
    verify(engine).rxClassifyBatch(requests, List.of());
  }

  @Test
  void the_default_batch_sends_every_request_at_once_and_keeps_their_order() {
    List<SingleSubject<ClassifyResponse>> pending = new ArrayList<>();
    when(engine.rxClassify(any())).thenAnswer(inv -> {
      var subject = SingleSubject.<ClassifyResponse>create();
      pending.add(subject);
      return subject;
    });

    var batch = engine
      .rxClassifyBatch(List.of(new ClassifyRequest("a"), new ClassifyRequest("b")))
      .test();
    assertThat(pending).as("both in flight before either completes").hasSize(2);

    pending.get(1).onSuccess(response("b"));
    pending.get(0).onSuccess(response("a"));

    batch.assertValue(results ->
      results.stream().map(ClassifyResponse::topLabel).toList().equals(List.of("a", "b"))
    );
  }

  private static ClassifyResponse response(String label) {
    return new ClassifyResponse(label, 1f, Map.of(label, 1f), List.of());
  }
}
