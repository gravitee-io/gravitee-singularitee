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

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.ClassifyResult;
import io.gravitee.singularitee.engine.api.ClassifyUsage;
import io.gravitee.singularitee.engine.api.ModelEngineToken;
import io.gravitee.singularitee.engine.api.ModelTasks;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.TextGenRequest;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.ConfidenceSignal;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.TokenConfidence;
import io.gravitee.singularitee.engine.classifier.LlmSpans.Item;
import io.gravitee.singularitee.engine.classifier.LlmSpans.Span;
import io.gravitee.singularitee.inference.api.text.EstimatedTokens;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter.Chunk;
import io.gravitee.singularitee.inference.api.textgen.PositionLogprobs;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.inference.api.textgen.TokenChannel;
import io.gravitee.singularitee.inference.api.textgen.TokenLogprobEntry;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A token classifier backed by a generative model that lists the entities it finds.
 *
 * <p>Each input is sent as the user turn after a fixed system prompt, decoded greedily, and
 * the answer, a list of items in the configured {@link LlmSpans.Format} (by default
 * {@code {"spans":[{"label":"...","text":"..."}]}}), is placed back into the input by
 * {@link LlmSpans}. With structured output on, decoding is constrained to the format's JSON
 * schema, so the answer can only hold items whose label and value sit where the format reads
 * them. With a score signal, the answer's token log-probabilities are kept and
 * each span scores the signal's confidence over the tokens that wrote its item, from the
 * item's opening brace to the end of its text; without one, or when the backend returns no
 * log-probabilities, spans score {@code 1.0}. The engine reports {@link ModelTasks#TOKEN_CLASSIFICATION}, so {@code /v1/classify},
 * the {@code guard} step and {@code composite_classifier} use it as any other span classifier.
 *
 * <p>An input longer than {@code chunkTokens} estimated tokens is split on semantic boundaries
 * and each piece asked separately, which keeps prompt and answer within the model's context.
 * An answer that is not span JSON fails the call: a detector that cannot read its model must
 * not report a clean text.
 *
 * <p>The text-generation engine belongs to the model registry; closing this engine leaves it
 * running.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlmTokenClassifierEngine implements ClassifierEngine {

  private static final Logger LOGGER = LoggerFactory.getLogger(LlmTokenClassifierEngine.class);

  /** Candidates per position: enough for margins and entropies, cheap to return. */
  static final int TOP_LOGPROBS = 5;

  private static final ClassifyResponse EMPTY_RESPONSE = new ClassifyResponse(
    null,
    0.0f,
    Map.of(),
    List.of()
  );

  /**
   * How the model is asked and its answer read.
   *
   * @param systemPrompt     system turn sent before every input; blank sends the input alone
   * @param labels           labels kept from the answer; empty keeps every label
   * @param maxTokens        answer budget in tokens
   * @param templateContext  chat-template variables, e.g. {@code enable_thinking: false}
   * @param chunkTokens      estimated tokens per request before the input is split; {@code 0} never splits
   * @param repeatMinLength  shortest value extended to its other occurrences; {@code 0} disables
   * @param score            signal scoring each span from its tokens' log-probabilities;
   *                         {@code null} scores every span {@code 1.0}
   * @param format           where the answer keeps its items, labels and values;
   *                         {@code null} is {@link LlmSpans.Format#DEFAULT}
   * @param structuredOutput whether decoding is constrained to {@code format}'s JSON schema
   */
  public record Options(
    String systemPrompt,
    List<String> labels,
    int maxTokens,
    Map<String, Object> templateContext,
    int chunkTokens,
    int repeatMinLength,
    ConfidenceSignal score,
    LlmSpans.Format format,
    boolean structuredOutput
  ) {
    public Options {
      systemPrompt = systemPrompt == null ? "" : systemPrompt;
      labels = labels == null ? List.of() : List.copyOf(labels);
      templateContext = templateContext == null ? Map.of() : Map.copyOf(templateContext);
      format = format == null ? LlmSpans.Format.DEFAULT : format;
      if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be > 0");
      if (chunkTokens < 0) throw new IllegalArgumentException("chunkTokens must be >= 0");
      if (score != null && score.confidence(0.0).isEmpty()) {
        throw new IllegalArgumentException(score.field() + " has no reading as a confidence");
      }
    }

    /** The default answer format, decoded unconstrained. */
    public Options(
      String systemPrompt,
      List<String> labels,
      int maxTokens,
      Map<String, Object> templateContext,
      int chunkTokens,
      int repeatMinLength,
      ConfidenceSignal score
    ) {
      this(
        systemPrompt,
        labels,
        maxTokens,
        templateContext,
        chunkTokens,
        repeatMinLength,
        score,
        null,
        false
      );
    }
  }

  private final String name;
  private final TextGenEngine llm;
  private final IntSupplier sequenceIds;
  private final Options options;
  private final Set<String> labels;
  private final RecursiveTextSplitter splitter;
  private final StructuredOutput structuredOutput;

  /**
   * @param name         id used in log and error messages
   * @param llm          the generating engine
   * @param sequenceIds  hands out sequence ids for {@code llm}, shared with its other callers
   * @param options      prompt, labels and budgets
   */
  public LlmTokenClassifierEngine(
    String name,
    TextGenEngine llm,
    IntSupplier sequenceIds,
    Options options
  ) {
    this.name = Objects.requireNonNull(name, "name");
    this.llm = Objects.requireNonNull(llm, "llm");
    this.sequenceIds = Objects.requireNonNull(sequenceIds, "sequenceIds");
    this.options = Objects.requireNonNull(options, "options");
    this.labels = Set.copyOf(options.labels());
    this.splitter = options.chunkTokens() > 0
      ? new RecursiveTextSplitter(EstimatedTokens::endOffsets, options.chunkTokens())
      : null;
    this.structuredOutput = options.structuredOutput()
      ? new StructuredOutput.JsonSchema(options.format().jsonSchema())
      : null;
  }

  /** How the model is asked and its answer read. */
  public Options options() {
    return options;
  }

  @Override
  public String task() {
    return ModelTasks.TOKEN_CLASSIFICATION;
  }

  @Override
  public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
    return rxClassify(request, List.of());
  }

  /** Request labels narrow the answer to those labels; the model is asked the same question. */
  @Override
  public Single<ClassifyResponse> rxClassify(ClassifyRequest request, List<ClassifyLabel> labels) {
    return classify(request.text(), pieces(request.text()), labels);
  }

  @Override
  public Single<ClassifyResponse> rxClassifyPresplit(ClassifyRequest request) {
    return classify(request.text(), whole(request.text()), List.of());
  }

  @Override
  public void close() {
    // The text-generation engine is owned by the model registry.
  }

  private List<Chunk> pieces(String text) {
    if (splitter == null || text == null || text.isEmpty()) return whole(text);
    List<Chunk> chunks = splitter.split(text);
    return chunks.size() <= 1 ? whole(text) : chunks;
  }

  private static List<Chunk> whole(String text) {
    String t = text == null ? "" : text;
    return List.of(new Chunk(t, 0, t.length()));
  }

  private Single<ClassifyResponse> classify(
    String text,
    List<Chunk> chunks,
    List<ClassifyLabel> requested
  ) {
    if (text == null || text.isBlank()) return Single.just(EMPTY_RESPONSE);
    Set<String> wanted = requested == null || requested.isEmpty()
      ? Set.of()
      : requested.stream().map(ClassifyLabel::name).collect(Collectors.toUnmodifiableSet());
    return Flowable.fromIterable(chunks)
      .concatMapEager(chunk -> spansOf(chunk).toFlowable())
      .toList()
      .map(perChunk -> {
        List<Span> spans = new ArrayList<>();
        perChunk.forEach(c -> spans.addAll(c.spans()));
        var usage = ClassifyUsage.sum(perChunk.stream().map(ChunkSpans::usage).toList());
        var kept = LlmSpans.filter(
          spans,
          label ->
            (labels.isEmpty() || labels.contains(label)) &&
            (wanted.isEmpty() || wanted.contains(label))
        );
        return toResponse(LlmSpans.allOccurrences(text, kept, options.repeatMinLength()), usage);
      });
  }

  /** The spans of one piece, shifted to the full input, and the tokens asking for them took. */
  private record ChunkSpans(List<Span> spans, ClassifyUsage usage) {}

  /** Asks the model about one piece and returns its spans shifted to the full input. */
  private Single<ChunkSpans> spansOf(Chunk chunk) {
    return generate(chunk.text()).map(answer -> {
      var items = LlmSpans.parse(answer.text(), options.format()).orElseThrow(() ->
        new IllegalStateException(
          "llm_token_classifier '" +
            name +
            "': the model answer is not span JSON" +
            (answer.finishReason() != null && answer.finishReason().startsWith("length")
                ? " (cut off: " +
                answer.finishReason() +
                "; raise max_tokens or lower chunk_tokens)"
                : "")
        )
      );
      if (options.score() != null) items = scored(items, answer.tokens());
      var located = LlmSpans.locate(chunk.text(), items);
      if (located.missing() > 0) {
        LOGGER.debug(
          "llm_token_classifier '{}': {} of {} item(s) not found in the input",
          name,
          located.missing(),
          items.size()
        );
      }
      List<Span> spans = chunk.start() == 0
        ? located.spans()
        : located
          .spans()
          .stream()
          .map(s -> s.shifted(chunk.start()))
          .toList();
      return new ChunkSpans(spans, answer.usage());
    });
  }

  /**
   * Each item scored with the signal's confidence over its own tokens. A token counts for one
   * item only: the item holding its last character, else the item holding its first. A token
   * that closes one item's text and opens the next ({@code "},{"}) thus counts for the item it
   * opens, where the choice to list another entity was made, while a token that only closes a
   * text ({@code ","} before {@code ref}) still counts for the item it closes. An item no token
   * with log-probabilities covers scores {@code 1.0}.
   */
  private List<Item> scored(List<Item> items, List<AnswerToken> tokens) {
    List<TokenConfidence> samples = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) samples.add(new TokenConfidence());
    for (AnswerToken t : tokens) {
      int owner = itemAt(items, t.end() - 1);
      if (owner < 0) owner = itemAt(items, t.start());
      if (owner < 0) continue;
      samples.get(owner).record(t.logprobs().chosen().logprob(), true, topLogprobs(t.logprobs()));
    }
    List<Item> out = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) {
      out.add(items.get(i).withScore(confidence(samples.get(i))));
    }
    return out;
  }

  /** Index of the item whose place in the answer holds character {@code at}, or {@code -1}. */
  private static int itemAt(List<Item> items, int at) {
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).from() <= at && at < items.get(i).to()) return i;
    }
    return -1;
  }

  private float confidence(TokenConfidence sample) {
    if (sample.size() == 0) return 1.0f;
    double value = options.score().compute(sample);
    double confidence = options.score().confidence(value).orElse(1.0);
    return (float) Math.clamp(confidence, 0.0, 1.0);
  }

  /** The position's candidates, descending, as {@link TokenConfidence#record} expects. */
  private static double[] topLogprobs(PositionLogprobs logprobs) {
    return logprobs
      .top()
      .stream()
      .map(TokenLogprobEntry::logprob)
      .sorted(Comparator.reverseOrder())
      .mapToDouble(Double::doubleValue)
      .toArray();
  }

  /** One answer token: its characters {@code [start, end)} in the answer and its logprobs. */
  private record AnswerToken(int start, int end, PositionLogprobs logprobs) {}

  private record Answer(
    String text,
    String finishReason,
    List<AnswerToken> tokens,
    ClassifyUsage usage
  ) {}

  /**
   * Runs one sequence to completion and returns its answer text, reasoning excluded. The token
   * stream is opened before the sequence is submitted, so no token is produced unobserved, and
   * disposing the call cancels the sequence.
   */
  private Single<Answer> generate(String input) {
    return Single.defer(() -> {
      int seqId = sequenceIds.getAsInt();
      var answer = new StringBuilder();
      var scored = new ArrayList<AnswerToken>();
      var finish = new AtomicReference<String>();
      var usage = new AtomicReference<ClassifyUsage>();
      Flowable<ModelEngineToken> tokens = llm.rxStream(seqId);
      return tokens
        .mergeWith(Flowable.defer(() -> llm.rxAddSequence(seqId, request(input)).toFlowable()))
        .doOnNext(t -> {
          if (t.isFinal()) {
            finish.set(t.finishReason());
            usage.set(new ClassifyUsage(t.promptTokens(), t.completionTokens()));
          } else if (t.channel() != TokenChannel.REASONING && t.token() != null) {
            int start = answer.length();
            answer.append(t.token());
            if (t.logprobs() != null && t.logprobs().chosen() != null) {
              scored.add(new AnswerToken(start, answer.length(), t.logprobs()));
            }
          }
        })
        .ignoreElements()
        .doOnDispose(() -> llm.cancelSequence(seqId))
        .toSingle(() -> {
          if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("llm_token_classifier '{}': answer seq {}:\n{}", name, seqId, answer);
          }
          return new Answer(answer.toString(), finish.get(), List.copyOf(scored), usage.get());
        });
    });
  }

  private TextGenRequest request(String input) {
    List<ChatTurn> messages = options.systemPrompt().isBlank()
      ? List.of(new ChatTurn(ChatRole.USER, input))
      : List.of(
        new ChatTurn(ChatRole.SYSTEM, options.systemPrompt()),
        new ChatTurn(ChatRole.USER, input)
      );
    return new TextGenRequest(
      null,
      messages,
      options.maxTokens(),
      0f, // greedy: the answer must copy the input, not paraphrase it
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      options.templateContext().isEmpty() ? null : options.templateContext(),
      null,
      options.score() == null ? null : TOP_LOGPROBS,
      structuredOutput
    );
  }

  /**
   * The highest-scoring span on top (the first one on a tie), each label at its best span's
   * score; without a score signal every score is {@code 1.0}, as for {@link RegexClassifierEngine}.
   * Each result carries its item's metadata ({@code ref}), and the response the tokens read and
   * written over every piece.
   */
  private static ClassifyResponse toResponse(List<Span> spans, ClassifyUsage usage) {
    if (spans.isEmpty()) return EMPTY_RESPONSE.withUsage(usage);
    Map<String, Float> scores = new LinkedHashMap<>();
    List<ClassifyResult> results = new ArrayList<>(spans.size());
    Span top = spans.getFirst();
    for (Span s : spans) {
      scores.merge(s.label(), s.score(), Math::max);
      results.add(
        new ClassifyResult(s.label(), s.score(), s.text(), s.start(), s.end(), s.metadata())
      );
      if (s.score() > top.score()) top = s;
    }
    return new ClassifyResponse(
      top.label(),
      top.score(),
      Map.copyOf(scores),
      List.copyOf(results),
      usage
    );
  }
}
