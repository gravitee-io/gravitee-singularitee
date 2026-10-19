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
package io.gravitee.singularitee.engine.api.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import io.gravitee.singularitee.protocol.MediaContent;
import io.gravitee.singularitee.protocol.RawInput;
import io.gravitee.singularitee.protocol.RawPrompt;
import io.gravitee.singularitee.protocol.RawScore;
import io.gravitee.singularitee.protocol.TemplatedInput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionMessagesTest {

  @Test
  void a_templated_request_survives_the_wire() {
    var state = new LinkedHashMap<String, Object>();
    state.put("ticket", 7L);
    state.put("tags", List.of("refund", true));
    var request = new DecisionRequest(
      new DecisionInput.Templated(
        state,
        List.of(
          new Question.Choice(
            "route",
            "Which team?",
            List.of(
              new Question.Option("billing", null),
              new Question.Option("shipping", "parcels")
            )
          ),
          new Question.Score("urgency", Map.of("text", "How urgent?"), List.of("later", "now")),
          new Question.Noul("angry", "Angry?", "shouting", null)
        )
      ),
      true
    );

    var back = DecisionMessages.request(DecisionMessages.request("decider", request));

    assertThat(back).isEqualTo(request);
  }

  @Test
  void a_raw_request_survives_the_wire() {
    var request = new DecisionRequest(
      new DecisionInput.Raw(
        List.of(
          new DecisionInput.RawPrompt(
            "route",
            QuestionKind.CHOICE,
            List.of("a", "b"),
            "Answer: (",
            null
          ),
          new DecisionInput.RawPrompt(
            "urgency",
            QuestionKind.SCORE,
            List.of("0", "1", "2"),
            "x",
            null
          )
        )
      ),
      false
    );

    var proto = DecisionMessages.request("decider", request);
    var back = (DecisionInput.Raw) DecisionMessages.request(proto).input();

    assertThat(proto.getRaw().getPrompts(1).getScore().getLevels()).isEqualTo(3);
    assertThat(back.prompts()).isEqualTo(((DecisionInput.Raw) request.input()).prompts());
  }

  @Test
  void answers_survive_the_wire() {
    var result = new DecisionResult(
      List.of(
        new Answer.Choice(
          "route",
          "billing",
          List.of(
            new Answer.OptionProbability("billing", 0.75),
            new Answer.OptionProbability("shipping", 0.25)
          ),
          0.5,
          List.of()
        ),
        new Answer.Score(
          "urgency",
          1.25,
          1,
          List.of(new Answer.OptionProbability("0", 0.5)),
          0.3,
          List.of()
        ),
        new Answer.Noul("angry", 0.125, List.of())
      ),
      77
    );

    var back = DecisionMessages.result(DecisionMessages.response("decider", result));

    assertThat(back).isEqualTo(result);
  }

  @Test
  void integers_stay_integers_through_a_protobuf_value() {
    assertThat(DecisionMessages.toJava(DecisionMessages.toValue(3))).isEqualTo(3L);
    assertThat(DecisionMessages.toJava(DecisionMessages.toValue(0.5))).isEqualTo(0.5);
  }

  @Test
  void images_are_refused_as_an_unsupported_modality() {
    var input = TemplatedInput.newBuilder()
      .setText("x")
      .addImages(MediaContent.getDefaultInstance())
      .build();

    assertThatThrownBy(() -> DecisionMessages.templated(input, false))
      .isInstanceOf(DecisionException.class)
      .extracting(e -> ((DecisionException) e).reason())
      .isEqualTo(DecisionException.Reason.UNSUPPORTED_MODALITY);
  }

  @Test
  void a_score_level_count_out_of_range_is_refused_before_anything_is_allocated() {
    for (int levels : List.of(Integer.MAX_VALUE, 100_000_000, 11, 1, 0, -1, Integer.MIN_VALUE)) {
      var input = RawInput.newBuilder()
        .addPrompts(
          RawPrompt.newBuilder()
            .setQuestionId("urgency")
            .setScore(RawScore.newBuilder().setLevels(levels))
            .setText("Urgency: ")
        )
        .build();

      assertThatThrownBy(() -> DecisionMessages.raw(input, false))
        .as("levels = %d", levels)
        .isInstanceOf(DecisionException.class)
        .hasMessageContaining("urgency")
        .extracting(e -> ((DecisionException) e).reason())
        .isEqualTo(DecisionException.Reason.INVALID_REQUEST);
    }
  }
}
