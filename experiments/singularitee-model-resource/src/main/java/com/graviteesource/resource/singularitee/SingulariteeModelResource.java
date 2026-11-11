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

import com.graviteesource.resource.singularitee.client.SingulariteeClassificationClient;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.reactive.api.context.DeploymentContext;
import io.gravitee.resource.ai_model.api.AiTextModelResource;
import io.gravitee.resource.ai_model.api.InferenceServiceClient;
import io.gravitee.resource.ai_model.api.model.PromptInput;
import io.gravitee.resource.ai_model.api.result.ClassifierResults;
import io.gravitee.secrets.api.el.EvaluatedSecretsMethods;
import io.gravitee.secrets.api.el.FieldKind;
import io.gravitee.secrets.api.el.SecretFieldAccessControl;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

/** Deployment-backed classification with resource-owned transport and credentials. */
public class SingulariteeModelResource
  extends AiTextModelResource<
    SingulariteeModelConfiguration,
    Object,
    ClassifierResults
  > {

  @Inject
  DeploymentContext deploymentContext;

  private volatile SingulariteeClassificationClient client;

  @Override
  public void doStart() {
    // The base implementation loads an embedded model; this resource owns a remote client.
    vertx = applicationContext.getBean(Vertx.class);
    client = new SingulariteeClassificationClient(
      vertx,
      configuration().remote()
    );
  }

  @Override
  public void doStop() {
    var current = client;
    client = null;
    if (current != null) current.close();
  }

  @Override
  protected String getModelName() {
    return configuration().remote().modelId();
  }

  @Override
  protected InferenceServiceClient<
    PromptInput,
    Object
  > buildInferenceServiceClient() {
    throw new UnsupportedOperationException(
      "Singularitee models use remote inference"
    );
  }

  @Override
  public Single<ClassifierResults> invokeModel(PromptInput input) {
    return Single.defer(() -> {
      var current = client;
      if (current == null) return Single.error(
        new IllegalStateException("Singularitee resource is not started")
      );
      return resolveAuthorization().flatMap(headers ->
        current.classify(input, headers, configuration().capability())
      );
    }).timeout(configuration().remote().timeoutMs(), TimeUnit.MILLISECONDS);
  }

  private Single<Map<String, String>> resolveAuthorization() {
    var expression = configuration().remote().authorization();
    if (expression == null || expression.isBlank()) return Single.just(
      Map.of()
    );
    return Single.defer(() -> {
      // Copy the deployment scope for every invocation; never retain a resolved credential.
      var engine = TemplateEngine.fromTemplateEngine(
        deploymentContext.getTemplateEngine()
      );
      engine
        .getTemplateContext()
        .setVariable(
          SecretFieldAccessControl.EL_VARIABLE,
          new SecretFieldAccessControl(
            true,
            FieldKind.PASSWORD,
            "remote.authorization"
          )
        );
      return engine
        .eval(expression, String.class)
        .filter(
          value ->
            !value.isBlank() &&
            !value.strip().equalsIgnoreCase("Bearer") &&
            !value.contains(EvaluatedSecretsMethods.SECRETS_FEATURE_DISABLED)
        )
        .switchIfEmpty(Single.error(authorizationFailure()))
        .map(value -> Map.of("Authorization", value));
    }).onErrorResumeNext(error -> Single.error(authorizationFailure()));
  }

  private static IllegalStateException authorizationFailure() {
    return new IllegalStateException(
      "Model authorization could not be resolved"
    );
  }
}
