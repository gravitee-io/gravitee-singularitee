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
package io.gravitee.singularitee.engine.classifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.ClassifierEngine.ClassifyLabel;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.ClassifyResult;
import io.gravitee.singularitee.engine.api.ClassifyUsage;
import io.gravitee.singularitee.engine.api.ModelEngineToken;
import io.gravitee.singularitee.engine.api.ModelTasks;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.TextGenRequest;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.ConfidenceSignal;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.LogprobSlope;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MaxTokenPerplexity;
import io.gravitee.singularitee.inference.api.textgen.PositionLogprobs;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.inference.api.textgen.TokenChannel;
import io.gravitee.singularitee.inference.api.textgen.TokenLogprobEntry;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.UnicastProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.Test;

class LlmTokenClassifierEngineTest {

  private static final String SYSTEM = "Find all personal data. Answer with JSON only.";

  /**
   * Answers each input with the text {@code answers} maps it to, one token per character, or
   * per piece of {@code tokenizer}. With {@code logprobs}, each answer token carries the logprob
   * it maps the token's start index to.
   */
  static class ScriptedLlm implements TextGenEngine {

    final Function<String, String> answers;
    final List<TextGenRequest> requests = new CopyOnWriteArrayList<>();
    final List<Integer> cancelled = new CopyOnWriteArrayList<>();
    private final Map<Integer, FlowableProcessor<ModelEngineToken>> streams =
      new ConcurrentHashMap<>();
    String finishReason = "stop";
    String reasoning = "";
    IntToDoubleFunction logprobs;
    Function<String, List<String>> tokenizer = a ->
      a.chars().mapToObj(Character::toString).toList();

    ScriptedLlm(Function<String, String> answers) {
      this.answers = answers;
    }

    @Override
    public void start(Consumer<ModelEngineToken> tokenConsumer) {}

    @Override
    public Flowable<ModelEngineToken> rxStream(int seqId) {
      FlowableProcessor<ModelEngineToken> p = UnicastProcessor.<
          ModelEngineToken
        >create().toSerialized();
      streams.put(seqId, p);
      return p;
    }

    @Override
    public Completable rxAddSequence(int seqId, TextGenRequest request) {
      requests.add(request);
      var stream = streams.remove(seqId);
      String input = ChatTurn.lastUserContent(request.messages()).orElseThrow();
      int i = 0;
      for (char c : reasoning.toCharArray()) {
        stream.onNext(token(seqId, String.valueOf(c), i++, false, null, TokenChannel.REASONING));
      }
      int k = 0;
      for (String text : tokenizer.apply(answers.apply(input))) {
        var token = token(seqId, text, i++, false, null, TokenChannel.ANSWER);
        if (logprobs != null) token = withLogprob(token, logprobs.applyAsDouble(k));
        stream.onNext(token);
        k += text.length();
      }
      // The prompt counts one token per input character; the answer one per emitted token.
      stream.onNext(
        new ModelEngineToken(seqId, "", i, true, finishReason, input.length(), i, 0, 0, null, null)
      );
      stream.onComplete();
      return Completable.complete();
    }

    @Override
    public void cancelSequence(int seqId) {
      cancelled.add(seqId);
    }

    @Override
    public void close() {}

    private static ModelEngineToken token(
      int seqId,
      String text,
      int index,
      boolean last,
      String finish,
      TokenChannel channel
    ) {
      return new ModelEngineToken(seqId, text, index, last, finish, 0, 0, 0, 0, null, channel);
    }

    /** The chosen token at {@code logprob}, its one alternative taking the rest of the mass. */
    private static ModelEngineToken withLogprob(ModelEngineToken t, double logprob) {
      var chosen = new TokenLogprobEntry(t.token(), 1, logprob, List.of());
      double rest = Math.log(Math.max(1e-9, 1.0 - Math.exp(logprob)));
      var other = new TokenLogprobEntry("?", 2, rest, List.of());
      return new ModelEngineToken(
        t.seqId(),
        t.token(),
        t.index(),
        false,
        null,
        0,
        0,
        0,
        0,
        null,
        t.channel(),
        new PositionLogprobs(chosen, List.of(other, chosen))
      );
    }
  }

  private static LlmTokenClassifierEngine engine(
    ScriptedLlm llm,
    List<String> labels,
    int chunkTokens
  ) {
    return engine(llm, labels, chunkTokens, null);
  }

  private static LlmTokenClassifierEngine engine(
    ScriptedLlm llm,
    List<String> labels,
    int chunkTokens,
    ConfidenceSignal score
  ) {
    var ids = new AtomicInteger();
    return new LlmTokenClassifierEngine(
      "pii",
      llm,
      ids::incrementAndGet,
      new LlmTokenClassifierEngine.Options(
        SYSTEM,
        labels,
        1500,
        Map.of("enable_thinking", false),
        chunkTokens,
        4,
        score
      )
    );
  }

  private static ClassifyResponse classify(LlmTokenClassifierEngine engine, String text) {
    return engine.rxClassify(new ClassifyRequest(text)).blockingGet();
  }

  @Test
  void reports_token_classification() {
    assertThat(engine(new ScriptedLlm(t -> "{\"spans\":[]}"), List.of(), 0).task()).isEqualTo(
      ModelTasks.TOKEN_CLASSIFICATION
    );
  }

  @Test
  void returns_located_spans_at_full_score() {
    var llm = new ScriptedLlm(t ->
      "{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Kari Nordmann\",\"ref\":\"p1\"},{\"label\":\"PHONE\",\"text\":\"912 34 567\",\"ref\":\"p1\"}]}"
    );
    var response = classify(engine(llm, List.of(), 0), "Ring Kari Nordmann på 912 34 567");

    assertThat(response.topLabel()).isEqualTo("PERSON");
    assertThat(response.topScore()).isEqualTo(1.0f);
    assertThat(response.allScores()).containsOnly(
      Map.entry("PERSON", 1.0f),
      Map.entry("PHONE", 1.0f)
    );
    assertThat(response.results())
      .extracting(
        ClassifyResult::label,
        ClassifyResult::token,
        ClassifyResult::start,
        ClassifyResult::end
      )
      .containsExactly(
        tuple("PERSON", "Kari Nordmann", 5, 18),
        tuple("PHONE", "912 34 567", 22, 32)
      );
  }

  @Test
  void sends_system_prompt_greedy_sampling_and_template_context() {
    var llm = new ScriptedLlm(t -> "{\"spans\":[]}");
    classify(engine(llm, List.of(), 0), "hei");

    var request = llm.requests.getFirst();
    assertThat(request.prompt()).isNull();
    assertThat(request.messages())
      .extracting(ChatTurn::role, ChatTurn::content)
      .containsExactly(tuple(ChatRole.SYSTEM, SYSTEM), tuple(ChatRole.USER, "hei"));
    assertThat(request.temperature()).isZero();
    assertThat(request.maxTokens()).isEqualTo(1500);
    assertThat(request.templateContext()).containsEntry("enable_thinking", false);
  }

  @Test
  void no_spans_is_the_empty_response() {
    var response = classify(
      engine(new ScriptedLlm(t -> "{\"spans\":[]}"), List.of(), 0),
      "Været er fint."
    );
    assertThat(response.topLabel()).isNull();
    assertThat(response.results()).isEmpty();
    assertThat(response.allScores()).isEmpty();
  }

  @Test
  void blank_input_does_not_call_the_model() {
    var llm = new ScriptedLlm(t -> "{\"spans\":[]}");
    assertThat(classify(engine(llm, List.of(), 0), "  ").results()).isEmpty();
    assertThat(llm.requests).isEmpty();
  }

  @Test
  void ignores_reasoning_tokens() {
    var llm = new ScriptedLlm(t -> "{\"spans\":[{\"label\":\"AGE\",\"text\":\"42\"}]}");
    llm.reasoning = "{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Kari\"}]}";
    var response = classify(engine(llm, List.of(), 0), "Kari er 42 år");
    assertThat(response.results()).extracting(ClassifyResult::label).containsExactly("AGE");
  }

  @Test
  void configured_labels_drop_the_others() {
    var llm = new ScriptedLlm(t ->
      "{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Kari\"},{\"label\":\"ORG\",\"text\":\"Telenor\"}]}"
    );
    var response = classify(engine(llm, List.of("PERSON"), 0), "Kari jobber i Telenor");
    assertThat(response.results()).extracting(ClassifyResult::label).containsExactly("PERSON");
  }

  @Test
  void request_labels_narrow_the_answer() {
    var llm = new ScriptedLlm(t ->
      "{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Kari\"},{\"label\":\"PHONE\",\"text\":\"912 34 567\"}]}"
    );
    var response = engine(llm, List.of(), 0)
      .rxClassify(new ClassifyRequest("Kari 912 34 567"), List.of(new ClassifyLabel("PHONE", "")))
      .blockingGet();
    assertThat(response.results()).extracting(ClassifyResult::label).containsExactly("PHONE");
  }

  @Test
  void marks_a_name_at_every_occurrence() {
    var llm = new ScriptedLlm(t -> "{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Nordmann\"}]}");
    var response = classify(engine(llm, List.of(), 0), "Nordmann ringte. Hilsen Nordmann");
    assertThat(response.results()).extracting(ClassifyResult::start).containsExactly(0, 24);
  }

  @Test
  void splits_long_input_and_shifts_spans_back() {
    String first = "Kari ringte i går. ".repeat(20);
    String second = "Ola svarte i dag. ".repeat(20);
    String text = first + "\n\n" + second;
    var llm = new ScriptedLlm(t ->
      t.contains("Kari")
        ? "{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Kari\"}]}"
        : "{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Ola\"}]}"
    );
    var response = classify(engine(llm, List.of(), 64), text);

    assertThat(llm.requests).hasSizeGreaterThan(1);
    for (ClassifyResult r : response.results()) {
      assertThat(text.substring(r.start(), r.end())).isEqualTo(r.token());
    }
    assertThat(response.results()).extracting(ClassifyResult::token).contains("Kari", "Ola");
  }

  @Test
  void an_answer_that_is_not_span_json_fails_the_call() {
    var llm = new ScriptedLlm(t -> "{\"spans\":[{\"label\":\"PERSON\",\"te");
    llm.finishReason = "length";
    var call = engine(llm, List.of(), 0).rxClassify(new ClassifyRequest("Kari")).test();
    call.assertError(
      e ->
        e instanceof IllegalStateException &&
        e.getMessage().contains("'pii'") &&
        e.getMessage().contains("cut off: length")
    );
  }

  @Test
  void disposing_the_call_cancels_the_sequence() {
    var llm = new ScriptedLlm(t -> "") {
      @Override
      public Completable rxAddSequence(int seqId, TextGenRequest request) {
        requests.add(request);
        return Completable.never();
      }
    };
    var call = engine(llm, List.of(), 0).rxClassify(new ClassifyRequest("Kari")).test();
    call.dispose();
    assertThat(llm.cancelled).containsExactly(1);
  }

  private static final String TWO_ITEMS =
    "{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Kari Nordmann\",\"ref\":\"p1\"},{\"label\":\"PHONE\",\"text\":\"912 34 567\",\"ref\":\"p1\"}]}";

  /** Every answer character certain except those of {@code doubtful}, at {@code p}. */
  private static IntToDoubleFunction doubtAt(String answer, String doubtful, double p) {
    int from = answer.indexOf(doubtful);
    int to = from + doubtful.length();
    return k -> k >= from && k < to ? Math.log(p) : 0.0;
  }

  @Test
  void without_a_score_signal_no_logprobs_are_requested() {
    var llm = new ScriptedLlm(t -> "{\"spans\":[]}");
    classify(engine(llm, List.of(), 0), "hei");
    assertThat(llm.requests.getFirst().topLogprobs()).isNull();
  }

  @Test
  void scores_each_span_from_the_tokens_of_its_own_item() {
    var llm = new ScriptedLlm(t -> TWO_ITEMS);
    llm.logprobs = doubtAt(TWO_ITEMS, "567", 0.4);
    var response = classify(
      engine(llm, List.of(), 0, new MaxTokenPerplexity()),
      "Ring Kari Nordmann på 912 34 567"
    );

    assertThat(llm.requests.getFirst().topLogprobs()).isEqualTo(
      LlmTokenClassifierEngine.TOP_LOGPROBS
    );
    assertThat(response.results())
      .extracting(ClassifyResult::label, ClassifyResult::score)
      .containsExactly(tuple("PERSON", 1.0f), tuple("PHONE", 0.4f));
    assertThat(response.allScores()).containsOnly(
      Map.entry("PERSON", 1.0f),
      Map.entry("PHONE", 0.4f)
    );
  }

  @Test
  void the_label_counts_but_the_ref_does_not() {
    var llm = new ScriptedLlm(t -> TWO_ITEMS);
    String answer = TWO_ITEMS;
    int label = answer.indexOf("PHONE");
    int ref = answer.lastIndexOf("p1");
    llm.logprobs = k -> k == label ? Math.log(0.5) : k == ref ? Math.log(0.01) : 0.0;
    var response = classify(
      engine(llm, List.of(), 0, new MaxTokenPerplexity()),
      "Ring Kari Nordmann på 912 34 567"
    );
    assertThat(response.results()).extracting(ClassifyResult::score).containsExactly(1.0f, 0.5f);
  }

  @Test
  void the_top_label_is_the_best_scored_span() {
    var llm = new ScriptedLlm(t -> TWO_ITEMS);
    llm.logprobs = doubtAt(TWO_ITEMS, "Nordmann", 0.3);
    var response = classify(
      engine(llm, List.of(), 0, new MaxTokenPerplexity()),
      "Ring Kari Nordmann på 912 34 567"
    );
    assertThat(response.topLabel()).isEqualTo("PHONE");
    assertThat(response.topScore()).isEqualTo(1.0f);
  }

  @Test
  void a_backend_without_logprobs_scores_one() {
    var llm = new ScriptedLlm(t -> TWO_ITEMS);
    var response = classify(
      engine(llm, List.of(), 0, new MaxTokenPerplexity()),
      "Ring Kari Nordmann på 912 34 567"
    );
    assertThat(response.results()).extracting(ClassifyResult::score).containsOnly(1.0f);
  }

  @Test
  void a_signal_with_no_confidence_reading_is_refused() {
    assertThatThrownBy(() ->
      new LlmTokenClassifierEngine.Options(SYSTEM, null, 10, null, 0, 0, new LogprobSlope())
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("logprob_slope");
  }

  /** One token per character, except {@code piece} (its first occurrence), kept whole. */
  private static Function<String, List<String>> keepWhole(String piece) {
    return answer -> {
      int at = answer.indexOf(piece);
      var tokens = new ArrayList<String>();
      for (int k = 0; k < answer.length(); k++) {
        if (k == at) {
          tokens.add(piece);
          k += piece.length() - 1;
        } else {
          tokens.add(String.valueOf(answer.charAt(k)));
        }
      }
      return tokens;
    };
  }

  @Test
  void a_token_closing_one_item_and_opening_the_next_counts_for_the_next() {
    String answer =
      "{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Kari Nordmann\"},{\"label\":\"PHONE\",\"text\":\"912 34 567\"}]}";
    String merged = "\"},{\"";
    var llm = new ScriptedLlm(t -> answer);
    llm.tokenizer = keepWhole(merged);
    int at = answer.indexOf(merged);
    llm.logprobs = k -> k == at ? Math.log(0.2) : 0.0;
    var response = classify(
      engine(llm, List.of(), 0, new MaxTokenPerplexity()),
      "Ring Kari Nordmann på 912 34 567"
    );
    assertThat(response.results())
      .extracting(ClassifyResult::label, ClassifyResult::score)
      .containsExactly(tuple("PERSON", 1.0f), tuple("PHONE", 0.2f));
  }

  @Test
  void a_token_closing_a_text_before_its_ref_counts_for_that_item() {
    String closing = "\",\"";
    var llm = new ScriptedLlm(t -> TWO_ITEMS);
    llm.tokenizer = keepWhole("\"Kari Nordmann" + closing);
    int at = TWO_ITEMS.indexOf("\"Kari Nordmann" + closing);
    llm.logprobs = k -> k == at ? Math.log(0.3) : 0.0;
    var response = classify(
      engine(llm, List.of(), 0, new MaxTokenPerplexity()),
      "Ring Kari Nordmann på 912 34 567"
    );
    assertThat(response.results())
      .extracting(ClassifyResult::label, ClassifyResult::score)
      .containsExactly(tuple("PERSON", 0.3f), tuple("PHONE", 1.0f));
  }

  @Test
  void each_result_carries_the_metadata_of_its_item() {
    var response = classify(
      engine(new ScriptedLlm(t -> TWO_ITEMS), List.of(), 0),
      "Ring Kari Nordmann på 912 34 567"
    );
    assertThat(response.results())
      .extracting(ClassifyResult::metadata)
      .containsOnly(Map.of("ref", "p1"));
  }

  @Test
  void without_structured_output_decoding_is_unconstrained() {
    var llm = new ScriptedLlm(t -> "{\"spans\":[]}");
    classify(engine(llm, List.of(), 0), "hei");
    assertThat(llm.requests.getFirst().structuredOutput()).isNull();
  }

  @Test
  void structured_output_sends_the_formats_schema_and_reads_its_fields() {
    var format = new LlmSpans.Format("entities", "type", "value", List.of("person"));
    var llm = new ScriptedLlm(t ->
      "{\"entities\":[{\"type\":\"PERSON\",\"value\":\"Jean Dupont\",\"person\":\"1\"}]}"
    );
    var engine = new LlmTokenClassifierEngine(
      "ner",
      llm,
      new AtomicInteger()::incrementAndGet,
      new LlmTokenClassifierEngine.Options(SYSTEM, null, 500, null, 0, 0, null, format, true)
    );
    var response = classify(engine, "Appelez Jean Dupont demain");

    assertThat(llm.requests.getFirst().structuredOutput()).isEqualTo(
      new StructuredOutput.JsonSchema(format.jsonSchema())
    );
    assertThat(response.results())
      .extracting(
        ClassifyResult::label,
        ClassifyResult::token,
        ClassifyResult::start,
        ClassifyResult::metadata
      )
      .containsExactly(tuple("PERSON", "Jean Dupont", 8, Map.of("person", "1")));
  }

  @Test
  void usage_sums_the_prompt_and_answer_tokens_of_every_piece() {
    String first = "Kari ringte i går. ".repeat(20);
    String second = "Ola svarte i dag. ".repeat(20);
    String text = first + "\n\n" + second;
    String none = "{\"spans\":[]}";
    var llm = new ScriptedLlm(t -> none);
    var response = classify(engine(llm, List.of(), 64), text);

    int pieces = llm.requests.size();
    int prompt = llm.requests
      .stream()
      .mapToInt(r -> ChatTurn.lastUserContent(r.messages()).orElseThrow().length())
      .sum();
    assertThat(pieces).isGreaterThan(1);
    assertThat(response.usage()).isEqualTo(new ClassifyUsage(prompt, pieces * none.length()));
  }
}
