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
package io.gravitee.singularitee.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.DecisionEngine;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.http.router.OpenAiRoutes;
import io.gravitee.singularitee.inference.api.decision.DecisionCapabilities;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.protocol.ChoiceAnswer;
import io.gravitee.singularitee.protocol.DecideRequest;
import io.gravitee.singularitee.protocol.DecideResponse;
import io.gravitee.singularitee.protocol.DecisionAnswer;
import io.gravitee.singularitee.protocol.DecisionBlocked;
import io.gravitee.singularitee.protocol.GetModelResponse;
import io.gravitee.singularitee.protocol.ListModelsResponse;
import io.gravitee.singularitee.protocol.ListPipelinesResponse;
import io.gravitee.singularitee.protocol.NoulAnswer;
import io.gravitee.singularitee.protocol.OptionProbability;
import io.gravitee.singularitee.protocol.Role;
import io.gravitee.singularitee.protocol.ScoreAnswer;
import io.gravitee.singularitee.protocol.TokenUsage;
import io.gravitee.singularitee.service.DecisionTargetException;
import io.gravitee.singularitee.service.GraviteeDecisionServiceImpl;
import io.gravitee.singularitee.service.GraviteeInferenceServiceImpl;
import io.gravitee.singularitee.service.GraviteeModelServiceImpl;
import io.gravitee.singularitee.service.GraviteePipelineServiceImpl;
import io.gravitee.singularitee.service.GraviteeVectorServiceImpl;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.server.StatusException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@code /v1/systemone} and {@code /v1/decisions} over HTTP against a mocked decision
 * service: the JSON to protocol translation one way, the two response shapes the other.
 */
class DecisionRoutesTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicReference<DecideRequest> sent = new AtomicReference<>();

  private Vertx vertx;
  private HttpServer server;
  private HttpClient client;
  private int port;
  private GraviteeDecisionServiceImpl decisions;
  private GraviteeInferenceServiceImpl inference;
  private GraviteeModelServiceImpl modelService;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    client = vertx.createHttpClient();
    decisions = mock(GraviteeDecisionServiceImpl.class);
    inference = mock(GraviteeInferenceServiceImpl.class);
    modelService = mock(GraviteeModelServiceImpl.class);
    var pipelineService = mock(GraviteePipelineServiceImpl.class);
    when(pipelineService.listPipelines(any())).thenReturn(
      Future.succeededFuture(ListPipelinesResponse.getDefaultInstance())
    );

    var modelRegistry = new ModelRegistry();
    modelRegistry.register("decider", "Decider", new FakeDecisionEngine(), token -> {});
    modelRegistry.register("hidden", "Hidden", new FakeDecisionEngine(), token -> {}, "", false);

    when(decisions.execute(any())).thenAnswer(inv -> {
      sent.set(inv.getArgument(0));
      return Future.succeededFuture(answers());
    });

    Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());
    OpenAiRoutes.mount(
      router,
      inference,
      mock(GraviteeVectorServiceImpl.class),
      decisions,
      modelService,
      pipelineService,
      modelRegistry,
      new PipelineRegistry(modelRegistry),
      true
    );
    server = vertx.createHttpServer();
    port = server
      .requestHandler(router)
      .listen(0)
      .toCompletionStage()
      .toCompletableFuture()
      .get(10, TimeUnit.SECONDS)
      .actualPort();
  }

  @AfterEach
  void tearDown() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private static DecideResponse answers() {
    return DecideResponse.newBuilder()
      .setModelId("decider")
      .addAnswers(
        DecisionAnswer.newBuilder()
          .setQuestionId("route")
          .setChoice(
            ChoiceAnswer.newBuilder()
              .setChoice("billing")
              .addProbabilities(
                OptionProbability.newBuilder().setKey("billing").setProbability(0.9)
              )
              .addProbabilities(
                OptionProbability.newBuilder().setKey("shipping").setProbability(0.1)
              )
              .setConfidence(0.8)
          )
      )
      .addAnswers(
        DecisionAnswer.newBuilder()
          .setQuestionId("angry")
          .setNoul(NoulAnswer.newBuilder().setProbabilityTrue(0.25))
      )
      .addAnswers(
        DecisionAnswer.newBuilder()
          .setQuestionId("urgency")
          .setScore(
            ScoreAnswer.newBuilder()
              .setScore(1.5)
              .setMode(2)
              .addProbabilities(OptionProbability.newBuilder().setKey("0").setProbability(0.25))
              .addProbabilities(OptionProbability.newBuilder().setKey("1").setProbability(0.25))
              .addProbabilities(OptionProbability.newBuilder().setKey("2").setProbability(0.5))
              .setConfidence(0.4)
          )
      )
      .setUsage(TokenUsage.newBuilder().setPromptTokens(42))
      .build();
  }

  @Test
  void systemone_translates_the_typesafe_shape_both_ways() throws Exception {
    Resp r = post(
      "/v1/systemone",
      """
      {"model": "decider", "state": {"ticket": 7, "text": "charged twice"},
       "questions": {
         "route": {"type": "choice", "instructions": "Which team?",
                   "criteria": {"shipping": "parcels", "billing": null}},
         "angry": {"type": "noul", "instructions": "Angry?", "criteria": {"true": "shouting"}},
         "urgency": {"type": "score", "instructions": "Urgent?", "criteria": ["later", "today", "now"]}
       }}
      """
    );

    assertThat(r.status()).isEqualTo(200);
    DecideRequest request = sent.get();
    assertThat(request.getModelId()).isEqualTo("decider");
    var input = request.getTemplated();
    assertThat(input.getJson().getStructValue().getFieldsMap()).containsKeys("ticket", "text");
    assertThat(input.getQuestionsList())
      .extracting(q -> q.getId())
      .containsExactly("route", "angry", "urgency");
    assertThat(input.getQuestions(0).getChoice().getOptionsList())
      .extracting(o -> o.getKey())
      .containsExactly("shipping", "billing");
    assertThat(input.getQuestions(0).getChoice().getOptions(1).hasDescription()).isFalse();
    assertThat(input.getQuestions(1).getNoul().getWhenTrue().getStringValue()).isEqualTo(
      "shouting"
    );
    assertThat(input.getQuestions(2).getScore().getLevelsCount()).isEqualTo(3);

    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/model").asText()).isEqualTo("decider");
    assertThat(n.at("/answers/route/type").asText()).isEqualTo("choice");
    assertThat(n.at("/answers/route/choice").asText()).isEqualTo("billing");
    assertThat(n.at("/answers/route/probabilities/billing").asDouble()).isEqualTo(0.9);
    assertThat(n.at("/answers/angry/noul").asDouble()).isEqualTo(0.25);
    assertThat(n.at("/answers/urgency/legend/2").asText()).isEqualTo("now");
    assertThat(n.at("/answers/urgency/probabilities/2").asDouble()).isEqualTo(0.5);
    assertThat(n.at("/usage/input_tokens").asInt()).isEqualTo(42);
    assertThat(n.at("/usage/output_tokens").asInt()).isZero();
  }

  @Test
  void systemone_reports_a_malformed_question_by_id() throws Exception {
    Resp r = post(
      "/v1/systemone",
      """
      {"model": "decider", "state": "x",
       "questions": {"urgency": {"type": "score", "instructions": "?", "criteria": ["only"]}}}
      """
    );

    assertThat(r.status()).isEqualTo(400);
    assertThat(mapper.readTree(r.body()).at("/error/message").asText()).isEqualTo(
      "questions.urgency: \"criteria\" must be an array of 2 to 10 levels"
    );
  }

  @Test
  void systemone_refuses_images() throws Exception {
    Resp r = post(
      "/v1/systemone",
      """
      {"model": "decider", "state": "x", "images": ["data:image/png;base64,AAA"],
       "questions": {"a": {"type": "noul", "instructions": "?"}}}
      """
    );

    assertThat(r.status()).isEqualTo(400);
    assertThat(mapper.readTree(r.body()).at("/error/code").asText()).isEqualTo(
      "unsupported_modality"
    );
  }

  @Test
  void decisions_takes_chat_messages_and_answers_in_the_openai_envelope() throws Exception {
    Resp r = post(
      "/v1/decisions",
      """
      {"model": "decider",
       "input": [{"role": "system", "content": "be terse"},
                 {"role": "user", "content": [{"type": "text", "text": "charged twice"}]}],
       "questions": [{"id": "route", "type": "choice", "instructions": "Team?",
                      "options": [{"key": "billing"}, {"key": "shipping", "description": "parcels"}]}],
       "include_raw_scores": true}
      """
    );

    assertThat(r.status()).isEqualTo(200);
    var input = sent.get().getTemplated();
    assertThat(sent.get().getIncludeRawScores()).isTrue();
    assertThat(input.getMessages().getMessagesList())
      .extracting(m -> m.getRole())
      .containsExactly(Role.ROLE_SYSTEM, Role.ROLE_USER);
    assertThat(input.getMessages().getMessages(1).getContent()).isEqualTo("charged twice");
    assertThat(
      input.getQuestions(0).getChoice().getOptions(1).getDescription().getStringValue()
    ).isEqualTo("parcels");

    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/id").asText()).startsWith("dec_");
    assertThat(n.at("/object").asText()).isEqualTo("decision");
    assertThat(n.at("/answers/0/question_id").asText()).isEqualTo("route");
    assertThat(n.at("/answers/0/probabilities/1/key").asText()).isEqualTo("shipping");
    assertThat(n.at("/answers/1/probability_true").asDouble()).isEqualTo(0.25);
    assertThat(n.at("/answers/2/mode").asInt()).isEqualTo(2);
    assertThat(n.at("/usage/prompt_tokens").asInt()).isEqualTo(42);
    assertThat(n.at("/usage/total_tokens").asInt()).isEqualTo(42);
  }

  @Test
  void decisions_takes_rendered_prompts() throws Exception {
    Resp r = post(
      "/v1/decisions",
      """
      {"model": "decider", "prompts": [
        {"id": "route", "type": "choice", "option_keys": ["billing", "shipping"], "prompt": "Answer: ("},
        {"id": "urgency", "type": "score", "levels": 3, "prompt_tokens": [1, 2, 3]}]}
      """
    );

    assertThat(r.status()).isEqualTo(200);
    var raw = sent.get().getRaw();
    assertThat(raw.getPrompts(0).getChoice().getOptionKeysList()).containsExactly(
      "billing",
      "shipping"
    );
    assertThat(raw.getPrompts(0).getText()).isEqualTo("Answer: (");
    assertThat(raw.getPrompts(1).getScore().getLevels()).isEqualTo(3);
    assertThat(raw.getPrompts(1).getTokens().getIdsList()).containsExactly(1, 2, 3);
  }

  @Test
  void decisions_refuses_questions_and_prompts_together() throws Exception {
    Resp r = post(
      "/v1/decisions",
      """
      {"model": "decider", "input": "x", "prompts": [{"id": "a", "type": "noul", "prompt": "x"}]}
      """
    );

    assertThat(r.status()).isEqualTo(400);
  }

  @Test
  void a_hidden_model_answers_like_an_unknown_one() throws Exception {
    Resp r = post(
      "/v1/decisions",
      """
      {"model": "hidden", "input": "x", "questions": [{"id": "a", "type": "noul", "instructions": "?"}]}
      """
    );

    assertThat(r.status()).isEqualTo(400);
    assertThat(mapper.readTree(r.body()).at("/error/code").asText()).isEqualTo("model_not_found");
  }

  @Test
  void service_failures_map_to_the_error_codes() throws Exception {
    String body = """
      {"model": "decider", "input": "x", "questions": [{"id": "a", "type": "noul", "instructions": "?"}]}
      """;

    when(decisions.execute(any())).thenReturn(
      Future.failedFuture(DecisionTargetException.notFound("decider"))
    );
    assertThat(code(post("/v1/decisions", body))).isEqualTo("model_not_found");

    when(decisions.execute(any())).thenReturn(
      Future.failedFuture(DecisionTargetException.unsupported("decider", "is not a decision model"))
    );
    assertThat(code(post("/v1/decisions", body))).isEqualTo("unsupported_model");

    when(decisions.execute(any())).thenReturn(
      Future.failedFuture(
        new DecisionException(DecisionException.Reason.UNSUPPORTED_INPUT, "raw only")
      )
    );
    assertThat(code(post("/v1/decisions", body))).isEqualTo("unsupported_input");

    when(decisions.execute(any())).thenReturn(
      Future.failedFuture(new DecisionException(DecisionException.Reason.EVALUATION_FAILED, "nan"))
    );
    Resp failed = post("/v1/decisions", body);
    assertThat(failed.status()).isEqualTo(500);
    assertThat(code(failed)).isEqualTo("server_error");
  }

  @Test
  void classify_reports_labels_a_decision_classifier_refuses_as_a_bad_request() throws Exception {
    when(inference.classifyBatch(any())).thenReturn(
      Future.failedFuture(
        new StatusException(GrpcStatus.INVALID_ARGUMENT, "labels: duplicate label 'a'")
      )
    );

    Resp r = post(
      "/v1/classify",
      """
      {"model": "decider", "input": "hello", "labels": [{"name": "a"}, {"name": "a"}]}"""
    );

    assertThat(r.status()).isEqualTo(400);
    JsonNode error = mapper.readTree(r.body()).at("/error");
    assertThat(error.at("/type").asText()).isEqualTo("invalid_request_error");
    assertThat(error.at("/message").asText()).isEqualTo("labels: duplicate label 'a'");
  }

  @Test
  void a_blocked_pipeline_answers_200_with_blocked() throws Exception {
    when(decisions.execute(any())).thenReturn(
      Future.succeededFuture(
        DecideResponse.newBuilder()
          .setModelId("decider")
          .setBlocked(DecisionBlocked.newBuilder().setCode("content_filter").setMessage("no cards"))
          .build()
      )
    );

    Resp r = post(
      "/v1/decisions",
      """
      {"model": "decider", "input": "x", "questions": [{"id": "a", "type": "noul", "instructions": "?"}]}
      """
    );

    JsonNode n = mapper.readTree(r.body());
    assertThat(r.status()).isEqualTo(200);
    assertThat(n.at("/answers").size()).isZero();
    assertThat(n.at("/blocked/code").asText()).isEqualTo("content_filter");
    assertThat(n.at("/blocked/message").asText()).isEqualTo("no cards");
  }

  @Test
  void a_decision_model_lists_its_output_modality() throws Exception {
    when(modelService.listModels(any())).thenReturn(
      Future.succeededFuture(
        ListModelsResponse.newBuilder()
          .addModels(
            GetModelResponse.newBuilder()
              .setModelId("decider")
              .setTask("decision")
              .addInputModalities("text")
          )
          .build()
      )
    );

    JsonNode n = mapper.readTree(get("/v1/models").body());

    assertThat(n.at("/data/0/type").asText()).isEqualTo("decision");
    assertThat(n.at("/data/0/output_modalities/0").asText()).isEqualTo("decisions");
    assertThat(n.at("/data/0/architecture/output_modalities/0").asText()).isEqualTo("decisions");
    assertThat(n.at("/data/0/architecture/input_modalities/0").asText()).isEqualTo("text");
  }

  private String code(Resp r) throws Exception {
    return mapper.readTree(r.body()).at("/error/code").asText();
  }

  private record Resp(int status, String body) {}

  private Resp post(String path, String body) throws Exception {
    return request(HttpMethod.POST, path, body);
  }

  private Resp get(String path) throws Exception {
    return request(HttpMethod.GET, path, null);
  }

  private Resp request(HttpMethod method, String path, String body) throws Exception {
    CompletableFuture<Resp> future = new CompletableFuture<>();
    client
      .request(method, port, "localhost", path)
      .compose(req -> {
        req.putHeader("content-type", "application/json");
        return body == null ? req.send() : req.send(body);
      })
      .compose(resp -> resp.body().map(buf -> new Resp(resp.statusCode(), buf.toString())))
      .onSuccess(future::complete)
      .onFailure(future::completeExceptionally);
    return future.get(10, TimeUnit.SECONDS);
  }

  /** Registry presence only: the service is mocked, so the engine is never called. */
  private static final class FakeDecisionEngine implements DecisionEngine {

    @Override
    public DecisionCapabilities capabilities() {
      return new DecisionCapabilities(DecisionFamily.CUSTOM, 26, true, true);
    }

    @Override
    public Single<DecisionResult> rxDecide(DecisionRequest request) {
      return Single.error(new UnsupportedOperationException());
    }

    @Override
    public void close() {}
  }
}
