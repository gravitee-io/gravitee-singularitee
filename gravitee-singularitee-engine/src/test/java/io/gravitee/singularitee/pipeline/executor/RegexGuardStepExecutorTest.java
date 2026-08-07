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
package io.gravitee.singularitee.pipeline.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.GuardAction;
import io.gravitee.singularitee.protocol.RegexEntityDef;
import io.gravitee.singularitee.protocol.RegexGuardStepConfig;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RegexGuardStepExecutor}.
 *
 * <p>Covers the unified patterns API where names are free-form labels
 * (spaces, dashes, etc. all valid) and the executor wraps each pattern
 * with positional named groups internally.
 */
class RegexGuardStepExecutorTest {

  private RegexGuardStepExecutor executor;

  @BeforeEach
  void setUp() {
    executor = new RegexGuardStepExecutor(new JinjaRenderer());
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static PipelineContext contextWith(String prompt) {
    return new PipelineContext(
      prompt,
      List.of(new ChatTurn(ChatRole.USER, prompt)),
      null,
      List.of(),
      null
    );
  }

  private static StepContext stepContext(PipelineContext pctx) {
    var ctx = mock(StepContext.class);
    when(ctx.pipelineContext()).thenReturn(pctx);
    when(ctx.rxNextStep(anyString())).thenReturn(Maybe.just("next"));
    return ctx;
  }

  private static RegexEntityDef entry(String name, String pattern) {
    return RegexEntityDef.newBuilder().setName(name).setPattern(pattern).build();
  }

  // ── No-config passthrough ─────────────────────────────────────────────────

  @Test
  void noPatterns_skipsStep() {
    var pctx = contextWith("anything");
    var ctx = stepContext(pctx);
    var cfg = RegexGuardStepConfig.newBuilder().setAction(GuardAction.GUARD_ACTION_REJECT).build();

    assertThat(executor.execute("r", cfg, ctx).blockingGet()).isEqualTo("next");
    assertThat(pctx.isHalted()).isFalse();
  }

  // ── REJECT / WARN — trigger mode ──────────────────────────────────────────

  @Nested
  class TriggerMode {

    @Test
    void noMatch_passesThrough() {
      var pctx = contextWith("Hello world");
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_REJECT)
        .addPatterns(entry("SSN", "\\b\\d{3}-\\d{2}-\\d{4}\\b"))
        .build();

      assertThat(executor.execute("r", cfg, ctx).blockingGet()).isEqualTo("next");
      assertThat(pctx.isHalted()).isFalse();
    }

    @Test
    void matchRejectsRequest() {
      var pctx = contextWith("My SSN is 123-45-6789");
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_REJECT)
        .addPatterns(entry("SSN", "\\b\\d{3}-\\d{2}-\\d{4}\\b"))
        .build();

      executor.execute("r", cfg, ctx);

      assertThat(pctx.isHalted()).isTrue();
    }

    @Test
    void matchWritesContextVariables() {
      var pctx = contextWith("My card 4111-1111-1111-1111");
      var ctx = stepContext(pctx);
      // Free-form name with a space — no Java identifier restriction
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_WARN)
        .addPatterns(entry("Credit Card", "\\b\\d{4}[\\s\\-]\\d{4}[\\s\\-]\\d{4}[\\s\\-]\\d{4}\\b"))
        .build();

      executor.execute("r", cfg, ctx);

      assertThat(pctx.get("r.triggered")).isEqualTo("true");
      assertThat(pctx.get("r.match")).isEqualTo("4111-1111-1111-1111");
      assertThat(pctx.get("r.entity_type")).isEqualTo("Credit Card");
    }

    @Test
    void firstMatchingPatternIsReported() {
      // SSN pattern listed first, credit-card listed second.
      // Input matches credit card only → P1 fires.
      var pctx = contextWith("Card: 4111-1111-1111-1111");
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_WARN)
        .addPatterns(entry("SSN", "\\b\\d{3}-\\d{2}-\\d{4}\\b"))
        .addPatterns(entry("Credit Card", "\\b\\d{4}[\\s\\-]\\d{4}[\\s\\-]\\d{4}[\\s\\-]\\d{4}\\b"))
        .build();

      executor.execute("r", cfg, ctx);

      assertThat(pctx.get("r.entity_type")).isEqualTo("Credit Card");
    }

    @Test
    void freeFormNameWithDashes() {
      var pctx = contextWith("IBAN: GB29NWBK60161331926819");
      var ctx = stepContext(pctx);
      // Name contains a dash — valid because users never write group syntax
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_WARN)
        .addPatterns(entry("Bank-IBAN", "[A-Z]{2}\\d{2}[A-Z0-9]{4}\\d{7}([A-Z0-9]?){0,16}"))
        .build();

      executor.execute("r", cfg, ctx);

      assertThat(pctx.get("r.entity_type")).isEqualTo("Bank-IBAN");
    }

    @Test
    void warnAction_doesNotHalt() {
      var pctx = contextWith("123-45-6789 is here");
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_WARN)
        .addPatterns(entry("SSN", "\\d{3}-\\d{2}-\\d{4}"))
        .build();

      Maybe<String> result = executor.execute("r", cfg, ctx);

      assertThat(result.blockingGet()).isEqualTo("next");
      assertThat(pctx.isHalted()).isFalse();
      assertThat(pctx.get(PipelineContext.KEY_GUARD_TRIGGERED)).isEqualTo("r");
    }

    @Test
    void customRejectionMessageRendered() {
      var pctx = contextWith("My SSN is 123-45-6789");
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_REJECT)
        .addPatterns(entry("SSN", "\\b\\d{3}-\\d{2}-\\d{4}\\b"))
        .setMessage("Blocked: {{ r.entity_type }} found")
        .build();

      executor.execute("r", cfg, ctx);

      assertThat(pctx.haltMessage()).isEqualTo("Blocked: SSN found");
    }
  }

  // ── REDACT mode ───────────────────────────────────────────────────────────

  @Nested
  class RedactMode {

    @Test
    void noMatch_writesOriginalToOutputField() {
      var pctx = contextWith("safe text");
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_REDACT)
        .setRedactWithEntityType(true)
        .addPatterns(entry("Email", "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"))
        .build();

      executor.execute("r", cfg, ctx);

      assertThat(pctx.get("r.output")).isEqualTo("safe text");
    }

    @Test
    void redactWithEntityType_replacesWithUppercasedName() {
      var pctx = contextWith("email me at foo@bar.com");
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_REDACT)
        .setRedactWithEntityType(true)
        .addPatterns(entry("Email Address", "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"))
        .build();

      executor.execute("r", cfg, ctx);

      // Name is upper-cased for the replacement token
      assertThat(pctx.get("r.output")).isEqualTo("email me at [EMAIL ADDRESS]");
    }

    @Test
    void redactWithoutEntityType_replacesWithRedacted() {
      var pctx = contextWith("email me at foo@bar.com");
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_REDACT)
        .setRedactWithEntityType(false)
        .addPatterns(entry("Email Address", "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"))
        .build();

      executor.execute("r", cfg, ctx);

      assertThat(pctx.get("r.output")).isEqualTo("email me at [REDACTED]");
    }

    @Test
    void multipleEntries_allRedacted() {
      var pctx = contextWith("Call +15551234567 or email foo@bar.com");
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_REDACT)
        .setRedactWithEntityType(true)
        .addPatterns(
          entry(
            "Phone Number",
            "\\+?[0-9][\\s\\-\\.]?\\(?[0-9]{3}\\)?[\\s\\-\\.]?[0-9]{3}[\\s\\-\\.]?[0-9]{4}"
          )
        )
        .addPatterns(entry("Email Address", "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"))
        .build();

      executor.execute("r", cfg, ctx);

      String out = pctx.get("r.output");
      assertThat(out).contains("[PHONE NUMBER]");
      assertThat(out).contains("[EMAIL ADDRESS]");
      assertThat(out).doesNotContain("foo@bar.com");
    }

    @Test
    void overlappingSpans_mergedIntoSingleReplacement() {
      // Two identical patterns both match the same text — merged into one span
      var pctx = contextWith("SSN: 123-45-6789");
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_REDACT)
        .setRedactWithEntityType(true)
        .addPatterns(entry("SSN", "\\d{3}-\\d{2}-\\d{4}"))
        .addPatterns(entry("SSN", "\\d{3}-\\d{2}-\\d{4}"))
        .build();

      executor.execute("r", cfg, ctx);

      String out = pctx.get("r.output");
      assertThat(out).isNotNull();
      assertThat(out).doesNotContain("123-45-6789");
      // Should not produce double [SSN][SSN]
      assertThat(out.indexOf("[SSN]")).isEqualTo(out.lastIndexOf("[SSN]"));
    }

    @Test
    void redact_updatesPromptAndMessages() {
      String original = "My email is foo@bar.com";
      var pctx = contextWith(original);
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_REDACT)
        .setRedactWithEntityType(true)
        .addPatterns(entry("Email", "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"))
        .build();

      executor.execute("r", cfg, ctx);

      // KEY_PROMPT updated so {{ prompt }} reflects the redaction
      assertThat(pctx.get(PipelineContext.KEY_PROMPT)).isEqualTo("My email is [EMAIL]");
      // messages updated so {{ messages }} / {{ history }} are consistent
      assertThat(pctx.messages())
        .extracting(ChatTurn::content)
        .containsExactly("My email is [EMAIL]");
    }

    @Test
    void entityTypesWrittenToContext() {
      var pctx = contextWith("foo@bar.com and +15551234567");
      var ctx = stepContext(pctx);
      var cfg = RegexGuardStepConfig.newBuilder()
        .setAction(GuardAction.GUARD_ACTION_REDACT)
        .setRedactWithEntityType(true)
        .addPatterns(entry("Email Address", "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"))
        .addPatterns(
          entry(
            "Phone Number",
            "\\+?[0-9][\\s\\-\\.]?\\(?[0-9]{3}\\)?[\\s\\-\\.]?[0-9]{3}[\\s\\-\\.]?[0-9]{4}"
          )
        )
        .build();

      executor.execute("r", cfg, ctx);

      assertThat(pctx.get("r.entity_types")).contains("Email Address");
      assertThat(pctx.get("r.entity_types")).contains("Phone Number");
    }
  }
}
