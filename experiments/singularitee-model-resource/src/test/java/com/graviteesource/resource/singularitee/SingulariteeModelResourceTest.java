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
package com.graviteesource.resource.singularitee;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.graviteesource.resource.singularitee.configuration.RemoteModelConfiguration;
import io.gravitee.el.TemplateEngine;
import io.gravitee.el.spel.context.SecuredResolver;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.DeploymentContext;
import io.gravitee.gateway.reactive.api.context.llm.LlmExecutionContext;
import io.gravitee.gateway.reactive.api.context.llm.LlmRequest;
import io.gravitee.gateway.reactive.api.context.llm.Role;
import io.gravitee.gateway.reactive.api.context.llm.Turn;
import io.gravitee.policy.ai.prompt.guard.rails.AiPromptGuardRailsPolicy;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.AiPromptGuardRailsConfiguration.PromptPreset;
import io.gravitee.policy.ai.prompt.guard.rails.configuration.RequestPolicy;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.resource.ai_model.api.AiTextModelResource;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.secrets.api.el.DelegatingEvaluatedSecretsMethods;
import io.gravitee.secrets.api.el.EvaluatedSecretsMethods;
import io.gravitee.secrets.api.el.FieldKind;
import io.gravitee.secrets.api.el.SecretFieldAccessControl;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

class SingulariteeModelResourceTest {

  // The gateway's secret discovery rewrites get(...) to a deployment-specific grant.
  private static final String GRANTED_AUTH =
    "Bearer {#secrets.fromGrant('deployment-A', #secret_field_access_control_var)}";
  private final AtomicReference<Single<String>> credential =
    new AtomicReference<>(Single.just("first-token"));
  private final List<SingulariteeModelResource> resources = new ArrayList<>();
  private final SecretFieldAccessControl originalScope =
    new SecretFieldAccessControl(false, null, null);
  private WireMockServer server;
  private Vertx vertx;
  private StaticApplicationContext applicationContext;
  private TemplateEngine engine;

  @BeforeEach
  void setUp() {
    server = new WireMockServer(0);
    server.start();
    vertx = Vertx.vertx();
    applicationContext = new StaticApplicationContext();
    applicationContext.getBeanFactory().registerSingleton("vertx", vertx);
    SecuredResolver.initialize(null);
    engine = TemplateEngine.templateEngine();
    engine
      .getTemplateContext()
      .setVariable(SecretFieldAccessControl.EL_VARIABLE, originalScope);
    var methods = mock(EvaluatedSecretsMethods.class);
    when(
      methods.fromGrant(eq("deployment-A"), any(SecretFieldAccessControl.class))
    ).thenAnswer(call -> {
      var scope = call.getArgument(1, SecretFieldAccessControl.class);
      assertThat(scope).isEqualTo(
        new SecretFieldAccessControl(
          true,
          FieldKind.PASSWORD,
          "remote.authorization"
        )
      );
      return credential.get();
    });
    engine
      .getTemplateContext()
      .setDeferredFunctionHolderVariable(
        "secrets",
        new DelegatingEvaluatedSecretsMethods(methods)
      );
  }

  @AfterEach
  void tearDown() throws Exception {
    for (var resource : resources) resource.stop();
    applicationContext.close();
    vertx.close().blockingAwait();
    server.stop();
  }

  @Test
  void released_policy_uses_original_invocation_for_both_capabilities_and_rotating_credentials()
    throws Exception {
    assertThat(
      AiTextModelResource.class.getProtectionDomain()
        .getCodeSource()
        .getLocation()
        .toString()
    ).endsWith("gravitee-resource-ai-model-api-2.2.3.jar");
    assertThat(
      AiPromptGuardRailsPolicy.class.getProtectionDomain()
        .getCodeSource()
        .getLocation()
        .toString()
    ).endsWith("gravitee-policy-ai-prompt-guard-rails-5.2.0.jar");
    for (var capability : new String[] {
      "token-classification",
      "text-classification",
    }) {
      server.resetRequests();
      var resource = resource(capability, GRANTED_AUTH, 5000L);
      credential.set(Single.just("first-token"));
      var benign = "token-classification".equals(capability)
        ? "{}"
        : "{\"sensitive\":0.01}";
      server.stubFor(
        post("/v1/classify").willReturn(
          okJson(
            "{\"model\":\"pii\",\"results\":[{\"scores\":" + benign + "}]}"
          )
        )
      );
      assertThat(invokePolicy(resource)).isEmpty();
      server.verify(
        1,
        postRequestedFor(urlEqualTo("/v1/classify")).withHeader(
          "Authorization",
          equalTo("Bearer first-token")
        )
      );
      credential.set(Single.just("rotated-token"));
      server.stubFor(
        post("/v1/classify").willReturn(
          okJson(
            """
            {"model":"pii","results":[{"scores":{"sensitive":0.95},"spans":[
              {"label":"EMAIL","score":0.97,"start":0,"end":5}
            ]}]}
            """
          )
        )
      );
      assertThat(invokePolicy(resource))
        .singleElement()
        .satisfies(failure -> assertThat(failure.statusCode()).isEqualTo(400));
      server.verify(
        1,
        postRequestedFor(urlEqualTo("/v1/classify")).withHeader(
          "Authorization",
          equalTo("Bearer rotated-token")
        )
      );
      assertThat(
        engine
          .getTemplateContext()
          .lookupVariable(SecretFieldAccessControl.EL_VARIABLE)
      ).isSameAs(originalScope);
      server.stubFor(
        post("/v1/classify").willReturn(temporaryRedirect("/other"))
      );
      assertThat(invokePolicy(resource))
        .singleElement()
        .satisfies(failure -> assertThat(failure.statusCode()).isEqualTo(500));
      server.verify(0, getRequestedFor(urlEqualTo("/other")));
    }
  }

  @Test
  void prefixed_missing_disabled_and_failed_credentials_never_reach_http()
    throws Exception {
    var resource = resource("token-classification", GRANTED_AUTH, 5000L);
    for (var value : new String[] {
      "",
      "  ",
      EvaluatedSecretsMethods.SECRETS_FEATURE_DISABLED,
    }) {
      credential.set(Single.just(value));
      assertThat(invokePolicy(resource))
        .singleElement()
        .satisfies(failure -> assertThat(failure.statusCode()).isEqualTo(500));
    }
    credential.set(
      Single.error(new IllegalStateException("private-credential"))
    );
    assertThatThrownBy(() ->
      resource.invokeModel(new PromptInput("hello")).blockingGet()
    )
      .hasMessage("Model authorization could not be resolved")
      .hasNoCause();
    var disabled = resource(
      "token-classification",
      "Bearer {#secrets.get('/model/credentials','token')}",
      5000L
    );
    assertThat(invokePolicy(disabled))
      .singleElement()
      .satisfies(failure -> assertThat(failure.statusCode()).isEqualTo(500));
    resource.deploymentContext = null;
    assertThat(invokePolicy(resource))
      .singleElement()
      .satisfies(failure -> assertThat(failure.statusCode()).isEqualTo(500));
    server.verify(0, postRequestedFor(urlEqualTo("/v1/classify")));
    assertThat(
      engine
        .getTemplateContext()
        .lookupVariable(SecretFieldAccessControl.EL_VARIABLE)
    ).isSameAs(originalScope);
  }

  @Test
  void owns_remote_lifecycle_and_bounds_secret_resolution() throws Exception {
    var resource = resource("token-classification", null, 5000L);
    server.verify(0, postRequestedFor(urlEqualTo("/v1/classify")));
    server.stubFor(
      post("/v1/classify").willReturn(
        okJson("{\"model\":\"pii\",\"results\":[{\"scores\":{}}]}")
      )
    );
    assertThat(
      resource.invokeModel(new PromptInput("hello")).blockingGet().results()
    ).isEmpty();
    server.verify(
      1,
      postRequestedFor(urlEqualTo("/v1/classify")).withoutHeader(
        "Authorization"
      )
    );
    resource.stop();
    assertThatThrownBy(() ->
      resource.invokeModel(new PromptInput("hello")).blockingGet()
    ).hasMessage("Singularitee resource is not started");
    resource.start();
    assertThat(
      resource.invokeModel(new PromptInput("hello")).blockingGet().results()
    ).isEmpty();
    server.resetRequests();
    credential.set(Single.never());
    var stalled = resource("token-classification", GRANTED_AUTH, 100L);
    stalled
      .invokeModel(new PromptInput("hello"))
      .test()
      .awaitDone(2, TimeUnit.SECONDS)
      .assertError(TimeoutException.class);
    server.verify(0, postRequestedFor(urlEqualTo("/v1/classify")));
  }

  private SingulariteeModelResource resource(
    String capability,
    String authorization,
    long timeout
  ) throws Exception {
    var configuration = new SingulariteeModelConfiguration(
      capability,
      new RemoteModelConfiguration(
        "singularitee",
        server.baseUrl(),
        "pii",
        authorization,
        timeout
      )
    );
    var resource = new SingulariteeModelResource() {
      @Override
      public SingulariteeModelConfiguration configuration() {
        return configuration;
      }
    };
    resource.deploymentContext = new DeploymentContext() {
      @Override
      public <T> T getComponent(Class<T> type) {
        return null;
      }

      @Override
      public TemplateEngine getTemplateEngine() {
        return engine;
      }
    };
    resource.setApplicationContext(applicationContext);
    resource.start();
    resources.add(resource);
    return resource;
  }

  private List<ExecutionFailure> invokePolicy(
    SingulariteeModelResource resource
  ) {
    var interruptions = new ArrayList<ExecutionFailure>();
    var ctx = mock(LlmExecutionContext.class);
    var request = mock(LlmRequest.class);
    when(request.llmParts(any())).thenReturn(
      List.of(new Turn(Role.USER, "hello", null, null, null, null))
    );
    when(ctx.request()).thenReturn(request);
    when(ctx.metrics()).thenReturn(new Metrics());
    when(ctx.withLogger(any())).thenAnswer(call -> call.getArgument(0));
    when(ctx.interruptWith(any(ExecutionFailure.class))).thenAnswer(call -> {
      interruptions.add(call.getArgument(0));
      return Completable.complete();
    });
    var manager = mock(ResourceManager.class);
    when(
      manager.getResource("singularitee", AiTextModelResource.class)
    ).thenReturn(resource);
    when(ctx.getComponent(ResourceManager.class)).thenReturn(manager);
    var configuration = new AiPromptGuardRailsConfiguration(
      "singularitee",
      PromptPreset.ALL_PROMPTS,
      null,
      null,
      null,
      RequestPolicy.BLOCK_REQUEST
    );
    assertThat(
      new AiPromptGuardRailsPolicy(configuration)
        .onRequest(ctx)
        .blockingAwait(3, TimeUnit.SECONDS)
    ).isTrue();
    verify(ctx, never()).getTemplateEngine();
    return interruptions;
  }
}
