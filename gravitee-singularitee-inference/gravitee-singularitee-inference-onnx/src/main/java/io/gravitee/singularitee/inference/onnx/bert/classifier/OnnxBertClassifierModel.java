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
package io.gravitee.singularitee.inference.onnx.bert.classifier;

import static io.gravitee.singularitee.inference.api.Constants.*;
import static java.lang.String.valueOf;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.jni.CharSpan;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.gravitee.singularitee.inference.api.classifier.ClassifierMode;
import io.gravitee.singularitee.inference.api.classifier.ClassifierResult;
import io.gravitee.singularitee.inference.api.classifier.ClassifierResults;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter.Chunk;
import io.gravitee.singularitee.inference.onnx.FloatTensor;
import io.gravitee.singularitee.inference.onnx.bert.OnnxBertInference;
import io.gravitee.singularitee.inference.onnx.bert.config.OnnxBertConfig;
import java.util.*;

/**
 * BERT sequence or token classifier over ONNX Runtime (e.g. DeBERTa, ModernBERT, MiniLM heads).
 *
 * <p>{@code CLASSIFIER_MODE} selects the head: {@code SEQUENCE} applies a sigmoid per label and
 * returns labels sorted by score; {@code TOKEN} applies a softmax per token and returns one
 * result per content token with its character span, dropping labels in
 * {@code DISCARDED_LABELS}. Labels come from {@code id2label} in {@code config.json} when
 * present, else {@code CLASSIFIER_LABELS}, else the class index. Inputs longer than the
 * sequence budget are split on semantic boundaries; see {@link #inferAll(List)}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class OnnxBertClassifierModel extends OnnxBertInference<String, ClassifierResults> {

  private static final String ID_2_LABEL = "id2label";
  private final List<String> labels;
  private final Set<String> discarded;

  public OnnxBertClassifierModel(OnnxBertConfig config) {
    super(config);
    this.labels = getLabels(config);
    this.discarded = Set.copyOf(config.get(DISCARDED_LABELS, List.of()));
  }

  private List<String> getLabels(OnnxBertConfig config) {
    if (configJson != null) {
      Map<?, ?> id2label = objectMapper.convertValue(
        configJson.get(ID_2_LABEL),
        LinkedHashMap.class
      );
      return id2label.values().stream().map(Object::toString).toList();
    }
    return config.get(CLASSIFIER_LABELS, List.of());
  }

  @Override
  public ClassifierResults infer(String input) {
    return inferAll(List.of(input)).getFirst();
  }

  /**
   * Sequence-classifies pre-split chunk texts (each must fit the sequence budget, see
   * {@link #split(String)}) in ONE batched forward pass, one result per text. This is the batch
   * entry point used by micro-batching callers to fuse chunks from concurrent requests into a
   * single GPU run. Only meaningful in {@code SEQUENCE} mode.
   */
  public List<ClassifierResults> classifySequences(List<String> chunkTexts) {
    if (chunkTexts.isEmpty()) {
      return List.of();
    }
    return getSequenceResults(encodeAll(chunkTexts));
  }

  /**
   * Token-classifies pre-split chunk texts (each must fit the sequence budget, see
   * {@link #split(String)}) in ONE batched forward pass, one result per text with character spans
   * relative to that text. The token counterpart of {@link #classifySequences(List)}: callers that
   * split a long input shift each span by its chunk's start. Only meaningful in {@code TOKEN} mode.
   */
  public List<ClassifierResults> classifyTokens(List<String> chunkTexts) {
    if (chunkTexts.isEmpty()) {
      return List.of();
    }
    return getTokenResults(encodeAll(chunkTexts));
  }

  /**
   * For a split input, emits one row per (label, split) tagged with that split's character span
   * (all labels per split). The headline score per label (the {@code max} across splits) is
   * derived downstream by {@code OnnxClassifierEngine}; keeping every (label, split) row lets
   * callers see the full per-passage distribution and locate which passage drove a label.
   */
  private ClassifierResults perSplitRows(List<ClassifierResults> perChunk, List<Chunk> chunks) {
    List<ClassifierResult> rows = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i++) {
      Chunk chunk = chunks.get(i);
      for (ClassifierResult r : perChunk.get(i).results()) {
        rows.add(new ClassifierResult(r.label(), r.score(), null, chunk.start(), chunk.end()));
      }
    }
    return new ClassifierResults(rows);
  }

  /**
   * Classifies each input. A long input is split on semantic boundaries into chunks that fit the
   * sequence budget, and the chunks of every input run in ONE batched forward pass, the same path
   * {@code OnnxClassifierEngine} takes through its micro-batcher. Sequence results keep one row
   * per (label, chunk) tagged with the chunk's span, so any chunk that triggers a label flags the
   * input; token spans are shifted back from their chunk to the original text.
   */
  @Override
  public List<ClassifierResults> inferAll(List<String> inputs) {
    boolean sequence = config.<ClassifierMode>get(CLASSIFIER_MODE) == ClassifierMode.SEQUENCE;
    List<List<Chunk>> chunksPerInput = inputs.stream().map(splitter::split).toList();

    // An input that fits runs whole in sequence mode, keeping the no-span result shape.
    List<String> texts = new ArrayList<>();
    for (int i = 0; i < inputs.size(); i++) {
      List<Chunk> chunks = chunksPerInput.get(i);
      if (sequence && chunks.size() <= 1) {
        texts.add(inputs.get(i));
      } else {
        chunks.forEach(chunk -> texts.add(chunk.text()));
      }
    }
    List<ClassifierResults> perText = sequence ? classifySequences(texts) : classifyTokens(texts);

    var results = new ArrayList<ClassifierResults>(inputs.size());
    int next = 0;
    for (List<Chunk> chunks : chunksPerInput) {
      if (sequence && chunks.size() <= 1) {
        results.add(perText.get(next++));
        continue;
      }
      List<ClassifierResults> own = perText.subList(next, next + chunks.size());
      next += chunks.size();
      results.add(sequence ? perSplitRows(own, chunks) : shiftedTokenRows(own, chunks));
    }
    return results;
  }

  /** Token rows of a split input, each span shifted by its chunk's start into the original text. */
  private static ClassifierResults shiftedTokenRows(
    List<ClassifierResults> perChunk,
    List<Chunk> chunks
  ) {
    List<ClassifierResult> rows = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i++) {
      int offset = chunks.get(i).start();
      for (ClassifierResult r : perChunk.get(i).results()) {
        rows.add(
          new ClassifierResult(
            r.label(),
            r.score(),
            r.token(),
            r.start() + offset,
            r.end() + offset
          )
        );
      }
    }
    return new ClassifierResults(rows);
  }

  private List<ClassifierResults> getTokenResults(EncodingResults encodingResult) {
    try (OrtSession.Result result1 = encodingResult.result()) {
      TokenInput input = this.getTokenLogits(result1.get(0));
      var results = new ArrayList<ClassifierResults>(input.batchSize());

      for (int i = 0; i < input.batchSize(); i++) {
        final Encoding encoding = encodingResult.encoding().get(i);

        final String[] tokens = encoding.getTokens();
        final CharSpan[] spans = encoding.getCharTokenSpans();

        long rowBase = i * input.logits().stride(0);
        int numLabels = input.logits().dim(2);
        var result = new ArrayList<ClassifierResult>();
        for (int j = 1; j < tokens.length - 1; j++) {
          final String sanitizedToken = tokens[j].trim();
          var classifierResult = computeTokenProb(
            input.logits().row(rowBase + (long) j * numLabels, numLabels),
            sanitizedToken,
            spans[j]
          );
          if (!discarded.contains(classifierResult.label())) {
            result.add(classifierResult);
          }
        }
        results.add(new ClassifierResults(result));
      }

      return results;
    }
  }

  private ClassifierResult computeTokenProb(float[] logit, String token, CharSpan span) {
    float[] probabilities = config.gioMath().softmax(logit);

    int argMax = 0;
    float maxProb = probabilities[0];

    for (int i = 1; i < probabilities.length; i++) {
      if (probabilities[i] > maxProb) {
        argMax = i;
        maxProb = probabilities[i];
      }
    }

    return new ClassifierResult(
      computeLabel(probabilities, argMax),
      maxProb,
      token,
      span.getStart(),
      span.getEnd()
    );
  }

  private List<ClassifierResults> getSequenceResults(EncodingResults encodingResult) {
    try (OrtSession.Result result = encodingResult.result()) {
      SequenceInput input = this.getSequenceInput(result.get(0));
      var results = new ArrayList<ClassifierResults>(input.batchSize());
      for (int i = 0; i < input.batchSize(); i++) {
        results.add(new ClassifierResults(computeSequenceProb(input.logits()[i])));
      }
      return results;
    }
  }

  private List<ClassifierResult> computeSequenceProb(float[] logit) {
    float[] probabilities = config.gioMath().sigmoid(logit);
    List<ClassifierResult> results = new ArrayList<>(probabilities.length);

    for (int j = 0; j < probabilities.length; j++) {
      results.add(new ClassifierResult(computeLabel(probabilities, j), probabilities[j]));
    }

    if (results.size() == 2) {
      var result1 = results.getFirst();
      var result2 = results.getLast();
      if (result1.score() < result2.score()) {
        results.set(0, result2);
        results.set(1, result1);
      }
    } else {
      results.sort(Comparator.comparing(ClassifierResult::score).reversed());
    }

    return results;
  }

  private String computeLabel(float[] probabilities, int j) {
    return !labels.isEmpty() && labels.size() == probabilities.length ? labels.get(j) : valueOf(j);
  }

  private SequenceInput getSequenceInput(OnnxValue value) {
    try {
      var logits = FloatTensor.of(value); // [batch][numLabels]
      return new SequenceInput(logits.dim(0), logits.rows(0, logits.dim(0), logits.dim(1)));
    } catch (OrtException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private TokenInput getTokenLogits(OnnxValue r) {
    try {
      var tokens = FloatTensor.of(r); // [batch][seqLen][numLabels]
      return new TokenInput(tokens.dim(0), tokens);
    } catch (OrtException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
