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
  public ClassifierResults infer(String string) {
    return switch (config.<ClassifierMode>get(CLASSIFIER_MODE)) {
      case SEQUENCE -> inferSequence(string);
      case TOKEN -> inferTokens(string);
    };
  }

  /**
   * Sequence classification. A long input is split on semantic boundaries, classified in one
   * batched pass, and merged by keeping the highest score per label — so any chunk that triggers a
   * label (e.g. a toxicity / guardrail category) flags the whole input.
   */
  private ClassifierResults inferSequence(String string) {
    List<Chunk> chunks = splitter.split(string);
    if (chunks.size() <= 1) {
      return getSequenceResults(encode(string)).getFirst();
    }
    List<String> texts = chunks.stream().map(Chunk::text).toList();
    return perSplitRows(classifySequences(texts), chunks);
  }

  /**
   * Sequence-classifies pre-split chunk texts (each must fit the sequence budget — see
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
   * For a split input, emits one row per (label, split) tagged with that split's character span
   * (all labels per split). The headline score per label — the {@code max} across splits — is
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
   * Token classification. The input is tokenized once; if it exceeds the sequence budget it is run
   * as overlapping token windows (overlap ≥ {@code TOKEN_WINDOW_OVERLAP}) so every token is
   * labelled with full surrounding context. Each token's prediction is taken from the window where
   * it sits most interior, so an entity sliced at one window's edge is whole in the next. Character
   * offsets come from the single full tokenization, so spans need no remapping.
   */
  private ClassifierResults inferTokens(String input) {
    Encoding full = tokenizer.encode(input, true, false);
    long[] ids = full.getIds();
    int contentCount = ids.length - 2; // exclude [CLS] and [SEP]
    if (contentCount <= 0) {
      return new ClassifierResults(List.of());
    }

    float[][] tokenLogits = windowedTokenLogits(ids, contentCount);

    String[] tokens = full.getTokens();
    CharSpan[] spans = full.getCharTokenSpans();
    var results = new ArrayList<ClassifierResult>();
    for (int c = 0; c < contentCount; c++) {
      int pos = c + 1; // position in the full sequence; [CLS] is at 0
      var result = computeTokenProb(tokenLogits[c], tokens[pos].trim(), spans[pos]);
      if (!discarded.contains(result.label())) {
        results.add(result);
      }
    }
    return new ClassifierResults(results);
  }

  /**
   * One logit row per content token, taken from the overlapping window where that token is most
   * interior. Inputs within budget run as a single window; otherwise windows of
   * {@link #sequenceBudget} tokens slide with the configured overlap.
   */
  private float[][] windowedTokenLogits(long[] ids, int contentCount) {
    int window = sequenceBudget;
    int overlap = Math.min(
      config.get(TOKEN_WINDOW_OVERLAP, TOKEN_WINDOW_OVERLAP_DEFAULT_VALUE),
      window - 1
    );
    int stride = Math.max(window - overlap, 1);
    long clsId = ids[0];
    long sepId = ids[ids.length - 1];

    float[][] best = new float[contentCount][];
    int[] bestInterior = new int[contentCount];
    Arrays.fill(bestInterior, -1);

    for (int start = 0; start < contentCount; start += stride) {
      int end = Math.min(start + window, contentCount);
      try (OrtSession.Result result = encode(window(ids, clsId, sepId, start, end))) {
        var logits = FloatTensor.of(result.get(0)); // [1][windowLen][numLabels]
        int numLabels = logits.dim(2);
        for (int c = start; c < end; c++) {
          int interior = Math.min(c - start, (end - 1) - c);
          if (interior > bestInterior[c]) {
            bestInterior[c] = interior;
            best[c] = logits.row((long) (1 + (c - start)) * numLabels, numLabels);
          }
        }
      } catch (OrtException e) {
        throw new IllegalArgumentException(e);
      }
      if (end == contentCount) {
        break;
      }
    }
    return best;
  }

  /** Builds a window {@code [CLS] ids[start..end) [SEP]}; content token c lives at {@code ids[c + 1]}. */
  private static long[] window(long[] ids, long clsId, long sepId, int start, int end) {
    long[] windowIds = new long[(end - start) + 2];
    windowIds[0] = clsId;
    System.arraycopy(ids, start + 1, windowIds, 1, end - start);
    windowIds[windowIds.length - 1] = sepId;
    return windowIds;
  }

  @Override
  public List<ClassifierResults> inferAll(List<String> input) {
    return switch (config.<ClassifierMode>get(CLASSIFIER_MODE)) {
      case SEQUENCE -> classifySequences(input);
      case TOKEN -> getTokenResults(encodeAll(input));
    };
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
          // we don't want all tokens to be present
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
