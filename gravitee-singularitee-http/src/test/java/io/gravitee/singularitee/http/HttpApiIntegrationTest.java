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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.ModelEngineToken;
import io.gravitee.singularitee.engine.ModelEngineType;
import io.gravitee.singularitee.engine.TextGenEngine;
import io.gravitee.singularitee.engine.TextGenRequest;
import io.gravitee.singularitee.http.router.OpenAiRoutes;
import io.gravitee.singularitee.protocol.ClassifyBatchResponse;
import io.gravitee.singularitee.protocol.ClassifyResponse;
import io.gravitee.singularitee.protocol.ClassifyResult;
import io.gravitee.singularitee.protocol.EmbedBatchItem;
import io.gravitee.singularitee.protocol.EmbedBatchResponse;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.FloatVector;
import io.gravitee.singularitee.protocol.GetModelRequest;
import io.gravitee.singularitee.protocol.GetModelResponse;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.ListModelsResponse;
import io.gravitee.singularitee.protocol.ListPipelinesResponse;
import io.gravitee.singularitee.protocol.ResponseCompleted;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.gravitee.singularitee.protocol.ResponseOutputTextDelta;
import io.gravitee.singularitee.protocol.StepRole;
import io.gravitee.singularitee.protocol.TokenUsage;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.registry.PipelineRegistry;
import io.gravitee.singularitee.service.GraviteeInferenceServiceImpl;
import io.gravitee.singularitee.service.GraviteeModelServiceImpl;
import io.gravitee.singularitee.service.GraviteePipelineServiceImpl;
import io.gravitee.singularitee.service.GraviteeVectorServiceImpl;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Boots the real vert.x-web router with the OpenAI routes and drives it over HTTP, mocking only the
 * engine/service boundary. Exercises the resolver, dispatch, formatter, SSE writer and JSON paths.
 */
class HttpApiIntegrationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private Vertx vertx;
  private HttpServer server;
  private HttpClient client;
  private int port;

  private GraviteeInferenceServiceImpl inference;
  private GraviteeVectorServiceImpl vector;
  private GraviteeModelServiceImpl modelService;
  private GraviteePipelineServiceImpl pipelineService;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    client = vertx.createHttpClient();

    inference = mock(GraviteeInferenceServiceImpl.class);
    vector = mock(GraviteeVectorServiceImpl.class);
    modelService = mock(GraviteeModelServiceImpl.class);
    pipelineService = mock(GraviteePipelineServiceImpl.class);

    ModelRegistry modelRegistry = new ModelRegistry();
    modelRegistry.register("llm", "LLM", new FakeTextGenEngine(), token -> {});
    modelRegistry.register(
      "internal-llm",
      "Internal LLM",
      new FakeTextGenEngine(),
      token -> {},
      "",
      false
    );
    PipelineRegistry pipelineRegistry = new PipelineRegistry(modelRegistry);

    // infer(req, ws): drive a thinking delta, two content deltas, then completion.
    doAnswer(inv -> {
      WriteStream<InferResponse> ws = inv.getArgument(1);
      ws.write(thinking("thinking..."));
      ws.write(delta("Hello"));
      ws.write(delta(" world"));
      ws.end(completed());
      return null;
    })
      .when(inference)
      .infer(any(), any());

    when(vector.embedBatch(any())).thenReturn(
      Future.succeededFuture(
        EmbedBatchResponse.newBuilder()
          .addItems(
            EmbedBatchItem.newBuilder()
              .setEmbedding(
                FloatVector.newBuilder().addValues(0.1f).addValues(0.2f).addValues(0.3f)
              )
              .setTokenCount(4)
          )
          .build()
      )
    );

    when(modelService.listModels(any())).thenReturn(
      Future.succeededFuture(
        ListModelsResponse.newBuilder()
          .addModels(
            GetModelResponse.newBuilder()
              .setModelId("llm")
              .setModelName("LLM")
              .setTask("text-generation")
          )
          .build()
      )
    );
    when(modelService.getModel(any())).thenAnswer(inv -> {
      String id = ((GetModelRequest) inv.getArgument(0)).getModelId();
      if (!id.equals("llm") && !id.equals("internal-llm")) {
        return Future.failedFuture("Model not found: " + id);
      }
      return Future.succeededFuture(
        GetModelResponse.newBuilder()
          .setModelId(id)
          .setTask("text-generation")
          .setHidden(id.equals("internal-llm"))
          .build()
      );
    });
    when(pipelineService.listPipelines(any())).thenReturn(
      Future.succeededFuture(ListPipelinesResponse.getDefaultInstance())
    );

    Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());
    OpenAiRoutes.mount(
      router,
      inference,
      vector,
      modelService,
      pipelineService,
      modelRegistry,
      pipelineRegistry,
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
    if (server != null) {
      server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
    if (vertx != null) {
      vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void chatNonStreaming() throws Exception {
    Resp r = post(
      "/v1/chat/completions",
      "{\"model\":\"llm\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
    );
    assertThat(r.status()).isEqualTo(200);
    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/object").asText()).isEqualTo("chat.completion");
    assertThat(n.at("/choices/0/message/content").asText()).isEqualTo("Hello world");
    assertThat(n.at("/choices/0/message/reasoning_content").asText()).isEqualTo("thinking...");
    assertThat(n.at("/choices/0/finish_reason").asText()).isEqualTo("stop");
    assertThat(n.at("/usage/prompt_tokens").asInt()).isEqualTo(5);
  }

  @Test
  void chatStreaming() throws Exception {
    Resp r = post(
      "/v1/chat/completions",
      "{\"model\":\"llm\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
    );
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body())
      .contains("chat.completion.chunk")
      .contains("\"role\":\"assistant\"")
      .contains("\"reasoning_content\":\"thinking...\"")
      .contains("\"content\":\"Hello\"")
      .contains("data: [DONE]");
  }

  @Test
  void responsesStreamingIsSpecCompliant() throws Exception {
    Resp r = post("/v1/responses", "{\"model\":\"llm\",\"stream\":true,\"input\":\"hi\"}");
    assertThat(r.status()).isEqualTo(200);
    String body = r.body();
    // wire violation #1: the Responses protocol has no [DONE] sentinel
    assertThat(body).doesNotContain("[DONE]");
    // fidelity: full item/content lifecycle around the text deltas
    assertThat(body)
      .contains("response.created")
      .contains("response.output_item.added")
      .contains("response.content_part.added")
      .contains("response.output_text.delta")
      .contains("response.output_text.done")
      .contains("response.output_item.done")
      .contains("response.completed");
    // reasoning is surfaced as a reasoning item via reasoning_summary_text.* events
    assertThat(body)
      .contains("response.reasoning_summary_text.delta")
      .contains("response.reasoning_summary_text.done")
      .contains("thinking...")
      .contains("\"type\":\"reasoning\"");
    // fidelity #1: response.completed embeds the assembled output text
    assertThat(body).contains("Hello world");
    // wire violation #2: created_at is seconds (~10 digits), not milliseconds (~13)
    var m = java.util.regex.Pattern.compile("\"created_at\":(\\d+)").matcher(body);
    assertThat(m.find()).isTrue();
    assertThat(m.group(1).length()).isLessThanOrEqualTo(10);
  }

  @Test
  void embeddings() throws Exception {
    Resp r = post("/v1/embeddings", "{\"model\":\"emb\",\"input\":\"hello\"}");
    assertThat(r.status()).isEqualTo(200);
    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/object").asText()).isEqualTo("list");
    assertThat(n.at("/data/0/embedding").size()).isEqualTo(3);
    assertThat(n.at("/usage/prompt_tokens").asInt()).isEqualTo(4);
  }

  @Test
  void sequenceClassificationHasNoSpans() throws Exception {
    when(inference.classifyBatch(any())).thenReturn(
      Future.succeededFuture(
        ClassifyBatchResponse.newBuilder()
          .addResults(
            ClassifyResponse.newBuilder()
              .setTopLabel("unsafe")
              .setTopScore(0.9f)
              // a per-label entry WITHOUT character offsets — must not become a span
              .addResults(ClassifyResult.newBuilder().setLabel("unsafe").setScore(0.9f).build())
              .build()
          )
          .build()
      )
    );
    Resp r = post("/v1/classify", "{\"model\":\"gliguard\",\"input\":\"some text\"}");
    assertThat(r.status()).isEqualTo(200);
    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/results/0/top_label").asText()).isEqualTo("unsafe");
    assertThat(n.at("/results/0/spans").isMissingNode()).isTrue();
  }

  @Test
  void tokenClassificationHasSpans() throws Exception {
    when(inference.classifyBatch(any())).thenReturn(
      Future.succeededFuture(
        ClassifyBatchResponse.newBuilder()
          .addResults(
            ClassifyResponse.newBuilder()
              .setTopLabel("EMAIL")
              .setTopScore(0.99f)
              .addResults(
                ClassifyResult.newBuilder()
                  .setLabel("EMAIL")
                  .setScore(0.99f)
                  .setStart(11)
                  .setEnd(27)
                  .build()
              )
              .build()
          )
          .build()
      )
    );
    Resp r = post("/v1/classify", "{\"model\":\"pii-ner\",\"input\":\"my email is a@b.com\"}");
    assertThat(r.status()).isEqualTo(200);
    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/results/0/spans/0/label").asText()).isEqualTo("EMAIL");
    assertThat(n.at("/results/0/spans/0/start").asInt()).isEqualTo(11);
    assertThat(n.at("/results/0/spans/0/end").asInt()).isEqualTo(27);
  }

  @Test
  void listModels() throws Exception {
    Resp r = get("/v1/models");
    assertThat(r.status()).isEqualTo(200);
    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/object").asText()).isEqualTo("list");
    assertThat(n.at("/data/0/id").asText()).isEqualTo("llm");
    assertThat(n.at("/data/0/object").asText()).isEqualTo("model");
  }

  @Test
  void hiddenModelIsNotAnEndpoint() throws Exception {
    Resp r = post(
      "/v1/chat/completions",
      "{\"model\":\"internal-llm\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
    );
    assertThat(r.status()).isEqualTo(400);
    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/error/code").asText()).isEqualTo("model_not_found");
  }

  @Test
  void hiddenModelIsNotAnEndpointOnTheVectorRoutes() throws Exception {
    Resp r = post("/v1/embeddings", "{\"model\":\"internal-llm\",\"input\":\"hello\"}");
    assertThat(r.status()).isEqualTo(400);
    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/error/code").asText()).isEqualTo("model_not_found");
  }

  @Test
  void hiddenModelIsNotRetrievableById() throws Exception {
    assertThat(get("/v1/models/llm").status()).isEqualTo(200);

    Resp r = get("/v1/models/internal-llm");
    assertThat(r.status()).isEqualTo(404);
    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/error/code").asText()).isEqualTo("model_not_found");
  }

  @Test
  void missingModelReturns400() throws Exception {
    Resp r = post("/v1/chat/completions", "{\"messages\":[]}");
    assertThat(r.status()).isEqualTo(400);
    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/error/param").asText()).isEqualTo("model");
  }

  @Test
  void schemaInvalidPayloadReturns400() throws Exception {
    // model present (passes requireModel) but messages is the wrong type → schema 400.
    Resp r = post("/v1/chat/completions", "{\"model\":\"llm\",\"messages\":\"notanarray\"}");
    assertThat(r.status()).isEqualTo(400);
    JsonNode n = mapper.readTree(r.body());
    assertThat(n.at("/error/type").asText()).isEqualTo("invalid_request_error");
  }

  // ── helpers ──

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

  private static InferResponse delta(String text) {
    return InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA)
      .setStepRole(StepRole.STEP_ROLE_OUTPUT)
      .setResponseOutputTextDelta(ResponseOutputTextDelta.newBuilder().setDelta(text).build())
      .build();
  }

  private static InferResponse thinking(String text) {
    return InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA)
      .setStepRole(StepRole.STEP_ROLE_THINKING)
      .setResponseOutputTextDelta(ResponseOutputTextDelta.newBuilder().setDelta(text).build())
      .build();
  }

  private static InferResponse completed() {
    return InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED)
      .setResponseCompleted(
        ResponseCompleted.newBuilder()
          .setUsage(TokenUsage.newBuilder().setPromptTokens(5).setCompletionTokens(2).build())
          .setFinishReason(FinishReason.FINISH_REASON_STOP)
          .build()
      )
      .build();
  }

  private static final class FakeTextGenEngine implements TextGenEngine {

    @Override
    public ModelEngineType type() {
      return ModelEngineType.TEXT_GEN;
    }

    @Override
    public String task() {
      return "text-generation";
    }

    @Override
    public void close() {}

    @Override
    public void start(Consumer<ModelEngineToken> tokenConsumer) {}

    @Override
    public Completable rxAddSequence(int seqId, TextGenRequest request) {
      return Completable.complete();
    }
  }
}
