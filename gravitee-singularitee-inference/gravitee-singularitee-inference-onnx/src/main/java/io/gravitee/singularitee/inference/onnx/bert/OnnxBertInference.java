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
package io.gravitee.singularitee.inference.onnx.bert;

import static ai.onnxruntime.OnnxTensor.createTensor;
import static io.gravitee.singularitee.inference.api.Constants.*;
import static java.lang.System.arraycopy;
import static java.nio.LongBuffer.wrap;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.jni.CharSpan;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter;
import io.gravitee.singularitee.inference.onnx.OnnxInference;
import io.gravitee.singularitee.inference.onnx.bert.config.OnnxBertConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class OnnxBertInference<INPUT, OUTPUT>
  extends OnnxInference<OnnxBertConfig, INPUT, OUTPUT> {

  protected static final ObjectMapper objectMapper = new ObjectMapper();
  protected final HuggingFaceTokenizer tokenizer;
  protected final JsonNode configJson;
  private final boolean hasTokenTypeIds;

  /** Maximum content tokens (no special tokens) the model accepts in a single sequence. */
  protected final int sequenceBudget;
  /** Splits oversized inputs into chunks that each fit {@link #sequenceBudget}. */
  protected final RecursiveTextSplitter splitter;

  protected OnnxBertInference(OnnxBertConfig onnxBertConfig) {
    super(onnxBertConfig);
    this.tokenizer = getTokenizer();
    this.configJson = getConfigJson();
    hasTokenTypeIds = session.getInputNames().contains(TOKEN_TYPE_IDS);
    this.sequenceBudget = config.get(MAX_SEQUENCE_LENGTH, MAX_SEQUENCE_LENGTH_DEFAULT_VALUE);
    this.splitter = newSplitter(sequenceBudget);
  }

  /** Number of content tokens (no special tokens) {@code text} encodes to. */
  protected int countTokens(String text) {
    return tokenizer.encode(text, false, false).getIds().length;
  }

  /** Exclusive character end-offset of each content token in {@code text}. */
  private int[] tokenEndOffsets(String text) {
    CharSpan[] spans = tokenizer.encode(text, false, false).getCharTokenSpans();
    int[] ends = new int[spans.length];
    for (int i = 0; i < spans.length; i++) {
      ends[i] = spans[i].getEnd();
    }
    return ends;
  }

  /** A splitter for this model's tokenizer bounded by {@code budget} content tokens per chunk. */
  protected RecursiveTextSplitter newSplitter(int budget) {
    return new RecursiveTextSplitter(this::tokenEndOffsets, budget);
  }

  /**
   * Splits {@code text} into chunks that each fit this model's sequence budget (real tokenizer
   * boundaries, semantic-boundary first). Exposed so batching callers can split on the request
   * thread and feed the chunks to a batch entry point.
   */
  /**
   * The per-sequence token budget: inputs longer than this must be split.
   *
   * <p>Exposed so a caller that has already measured its input can tell whether
   * {@link #split(String)} would do anything, and skip a redundant tokenizer pass when it
   * would not.
   */
  public int sequenceBudget() {
    return sequenceBudget;
  }

  public List<RecursiveTextSplitter.Chunk> split(String text) {
    return splitter.split(text);
  }

  private HuggingFaceTokenizer getTokenizer() {
    try {
      return HuggingFaceTokenizer.newInstance(
        config.getResource().getTokenizer().toAbsolutePath(),
        config.getTokenizerConfig()
      );
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private JsonNode getConfigJson() {
    try {
      Path configJson = this.config.getResource().getConfigJson();
      return configJson == null
        ? null
        : objectMapper.readTree(String.join("", Files.readAllLines(configJson)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected EncodingResults encode(String sentence) {
    var encoding = tokenizer.encode(sentence, true, false);

    long[] inputIds = encoding.getIds();
    long[] attentionMask = encoding.getAttentionMask();

    long[] shape = { 1, inputIds.length };

    try (
      var inputIdsTensor = createTensor(environment, wrap(inputIds), shape);
      var attentionMaskTensor = createTensor(environment, wrap(attentionMask), shape);
      var tokenTypeIdsTensor = hasTokenTypeIds
        ? createTensor(environment, wrap(encoding.getTypeIds()), shape)
        : null;
    ) {
      var inputs = new HashMap<String, OnnxTensor>();
      inputs.put(INPUT_IDS, inputIdsTensor);
      inputs.put(ATTENTION_MASK, attentionMaskTensor);

      if (tokenTypeIdsTensor != null) {
        inputs.put(TOKEN_TYPE_IDS, tokenTypeIdsTensor);
      }
      return new EncodingResults(List.of(encoding), session.run(inputs));
    } catch (OrtException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Run the model on a ready-made token-id window (the caller has already added the
   * leading {@code [CLS]} and trailing {@code [SEP]}). The attention mask is all-ones
   * (no padding) and token type ids all-zeros (single segment). Unlike
   * {@link #encode(String)} this does not re-tokenize, so a caller that has already
   * tokenized the full input can embed individual token windows without re-tokenizing —
   * or re-running the model on the whole input — per window.
   */
  protected Result encode(long[] inputIds) {
    long[] attentionMask = new long[inputIds.length];
    Arrays.fill(attentionMask, 1L);

    long[] shape = { 1, inputIds.length };

    try (
      var inputIdsTensor = createTensor(environment, wrap(inputIds), shape);
      var attentionMaskTensor = createTensor(environment, wrap(attentionMask), shape);
      var tokenTypeIdsTensor = hasTokenTypeIds
        ? createTensor(environment, wrap(new long[inputIds.length]), shape)
        : null;
    ) {
      var inputs = new HashMap<String, OnnxTensor>();
      inputs.put(INPUT_IDS, inputIdsTensor);
      inputs.put(ATTENTION_MASK, attentionMaskTensor);

      if (tokenTypeIdsTensor != null) {
        inputs.put(TOKEN_TYPE_IDS, tokenTypeIdsTensor);
      }
      return session.run(inputs);
    } catch (OrtException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /** Encode a single (query, document) pair. Tokenizer auto-handles [CLS] q [SEP] d [SEP] + token type IDs. */
  protected EncodingResults encodePair(String queryText, String docText) {
    var encoding = tokenizer.encode(queryText, docText, true, false);

    long[] inputIds = encoding.getIds();
    long[] attentionMask = encoding.getAttentionMask();

    long[] shape = { 1, inputIds.length };

    try (
      var inputIdsTensor = createTensor(environment, wrap(inputIds), shape);
      var attentionMaskTensor = createTensor(environment, wrap(attentionMask), shape);
      var tokenTypeIdsTensor = hasTokenTypeIds
        ? createTensor(environment, wrap(encoding.getTypeIds()), shape)
        : null;
    ) {
      var inputs = new HashMap<String, OnnxTensor>();
      inputs.put(INPUT_IDS, inputIdsTensor);
      inputs.put(ATTENTION_MASK, attentionMaskTensor);

      if (tokenTypeIdsTensor != null) {
        inputs.put(TOKEN_TYPE_IDS, tokenTypeIdsTensor);
      }
      return new EncodingResults(List.of(encoding), session.run(inputs));
    } catch (OrtException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /** Encode a query against multiple documents as a batch of pairs. */
  protected EncodingResults encodeAllPairs(String queryText, List<String> docTexts) {
    List<Encoding> encodings = new ArrayList<>(docTexts.size());
    int maxTokens = 0;

    for (String docText : docTexts) {
      var encoding = tokenizer.encode(queryText, docText, true, false);
      maxTokens = Math.max(maxTokens, encoding.getIds().length);
      encodings.add(encoding);
    }

    int batchSize = docTexts.size();
    long[] inputIds = new long[batchSize * maxTokens];
    long[] attentionMask = new long[batchSize * maxTokens];
    long[] tokenTypeIds = hasTokenTypeIds ? new long[batchSize * maxTokens] : null;

    for (int i = 0; i < batchSize; i++) {
      Encoding encoding = encodings.get(i);

      long[] sInputIds = encoding.getIds();
      long[] sMask = encoding.getAttentionMask();

      int start = i * maxTokens;
      arraycopy(sInputIds, 0, inputIds, start, sInputIds.length);
      arraycopy(sMask, 0, attentionMask, start, sMask.length);
      if (hasTokenTypeIds) {
        long[] sTypeIds = encoding.getTypeIds();
        arraycopy(sTypeIds, 0, tokenTypeIds, start, sTypeIds.length);
      }
    }

    long[] shape = { batchSize, maxTokens };

    try (
      var inputIdsTensor = createTensor(environment, wrap(inputIds), shape);
      var attentionMaskTensor = createTensor(environment, wrap(attentionMask), shape);
      var tokenTypeIdsTensor = hasTokenTypeIds
        ? createTensor(environment, wrap(tokenTypeIds), shape)
        : null;
    ) {
      var inputs = new HashMap<String, OnnxTensor>();
      inputs.put(INPUT_IDS, inputIdsTensor);
      inputs.put(ATTENTION_MASK, attentionMaskTensor);

      if (tokenTypeIdsTensor != null) {
        inputs.put(TOKEN_TYPE_IDS, tokenTypeIdsTensor);
      }
      return new EncodingResults(encodings, session.run(inputs));
    } catch (OrtException e) {
      throw new IllegalArgumentException(e);
    }
  }

  protected EncodingResults encodeAll(List<String> sentences) {
    List<Encoding> encodings = new ArrayList<>(sentences.size());
    int maxTokens = 0;

    for (String sentence : sentences) {
      var encoding = tokenizer.encode(sentence, true, false);
      maxTokens = Math.max(maxTokens, encoding.getIds().length);
      encodings.add(encoding);
    }

    long[] inputIds = new long[sentences.size() * maxTokens];
    long[] attentionMask = new long[sentences.size() * maxTokens];
    long[] tokenTypeIds = hasTokenTypeIds ? new long[sentences.size() * maxTokens] : null;

    for (int i = 0; i < sentences.size(); i++) {
      Encoding encoding = encodings.get(i);

      // Retrieve the tokens for the current sentence
      long[] sentenceInputIds = encoding.getIds();
      long[] sentenceAttentionMask = encoding.getAttentionMask();

      int startIndex = i * maxTokens;
      arraycopy(sentenceInputIds, 0, inputIds, startIndex, sentenceInputIds.length);
      arraycopy(sentenceAttentionMask, 0, attentionMask, startIndex, sentenceAttentionMask.length);
      if (hasTokenTypeIds) {
        long[] sentenceTokenTypeIds = encoding.getTypeIds();
        arraycopy(sentenceTokenTypeIds, 0, tokenTypeIds, startIndex, sentenceTokenTypeIds.length);
      }
    }

    long[] shape = { sentences.size(), maxTokens };

    try (
      var inputIdsTensor = createTensor(environment, wrap(inputIds), shape);
      var attentionMaskTensor = createTensor(environment, wrap(attentionMask), shape);
      var tokenTypeIdsTensor = hasTokenTypeIds
        ? createTensor(environment, wrap(tokenTypeIds), shape)
        : null;
    ) {
      var inputs = new HashMap<String, OnnxTensor>();
      inputs.put(INPUT_IDS, inputIdsTensor);
      inputs.put(ATTENTION_MASK, attentionMaskTensor);

      if (tokenTypeIdsTensor != null) {
        inputs.put(TOKEN_TYPE_IDS, tokenTypeIdsTensor);
      }
      return new EncodingResults(encodings, session.run(inputs));
    } catch (OrtException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public record EncodingResults(List<Encoding> encoding, Result result) {}
}
