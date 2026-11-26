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
package io.gravitee.singularitee.engine.api.tracing;

import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.singularitee.engine.api.ModelEngine;
import io.vertx.core.Context;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The W3C trace context ({@code traceparent}, {@code tracestate}) a remote call carries, so the
 * node that serves it continues the caller's trace.
 *
 * <p>Engines take no tracing arguments, so the headers travel beside the call: they are bound to
 * the calling thread for the duration of a call into the engine, and a {@link
 * PropagatesTraceContext} engine reads {@link #current()} while it builds its RPC.
 */
public final class TraceContext {

  private static final ThreadLocal<Map<String, String>> BOUND = new ThreadLocal<>();

  private TraceContext() {}

  /** The headers bound to the call running on this thread; empty outside one. */
  public static Map<String, String> current() {
    Map<String, String> headers = BOUND.get();
    return headers == null ? Map.of() : headers;
  }

  /** Runs {@code call} with {@code headers} bound, restoring what was bound before. */
  public static <T> T callWith(Map<String, String> headers, Supplier<T> call) {
    Map<String, String> previous = BOUND.get();
    BOUND.set(headers);
    try {
      return call.get();
    } finally {
      if (previous == null) {
        BOUND.remove();
      } else {
        BOUND.set(previous);
      }
    }
  }

  /** The W3C headers naming {@code span} as the parent; empty when tracing is off. */
  public static Map<String, String> headersOf(Tracer tracer, Context vertxContext, Span span) {
    if (tracer == null || span == null) {
      return Map.of();
    }
    var headers = new LinkedHashMap<String, String>();
    tracer.injectSpanContext(vertxContext, span, headers::put);
    return Map.copyOf(headers);
  }

  /**
   * {@code engine} seen through {@code engineType}, every call into it made with {@code headers}
   * bound. A local engine, or no headers, returns {@code engine} itself.
   */
  public static <E extends ModelEngine> E bind(
    Class<E> engineType,
    E engine,
    Map<String, String> headers
  ) {
    if (
      headers.isEmpty() || !(engine instanceof PropagatesTraceContext) || !engineType.isInterface()
    ) {
      return engine;
    }
    Object bound = Proxy.newProxyInstance(
      engineType.getClassLoader(),
      new Class<?>[] { engineType },
      (proxy, method, args) ->
        callWith(headers, () -> {
          try {
            return method.invoke(engine, args);
          } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
          } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
          }
        })
    );
    return engineType.cast(bound);
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
    throw (T) t;
  }
}
