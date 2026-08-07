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
package io.gravitee.singularitee.adapter.textgen;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.engine.MediaAttachment;
import io.gravitee.singularitee.engine.MediaAttachmentType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the media-marker injection used on the direct-model path: the rendered prompt must carry
 * exactly one marker per attachment, or {@code mtmd_tokenize} fails with "number of media markers
 * in text (N) does not match number of bitmaps (M)".
 */
class AbstractTextGenEngineMediaMarkerTest {

  private static final String M = "<__media__>";

  private static MediaAttachment img() {
    return new MediaAttachment(MediaAttachmentType.IMAGE_JPEG, "AAAA");
  }

  @Test
  void prependsOneMarkerPerAttachmentInOrder() {
    var turns = List.of(new ChatTurn(ChatRole.USER, "describe this", List.of(img(), img(), img())));

    var out = AbstractTextGenEngine.injectMediaMarkers(turns, M);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).content()).isEqualTo(M + "\n" + M + "\n" + M + "\n" + "describe this");
    // media list is preserved for downstream bitmap extraction
    assertThat(out.get(0).media()).hasSize(3);
  }

  @Test
  void leavesTextOnlyTurnsUntouched() {
    var turns = List.of(
      new ChatTurn(ChatRole.SYSTEM, "you are helpful", List.of()),
      new ChatTurn(ChatRole.USER, "hello", List.of())
    );

    var out = AbstractTextGenEngine.injectMediaMarkers(turns, M);

    assertThat(out.get(0).content()).isEqualTo("you are helpful");
    assertThat(out.get(1).content()).isEqualTo("hello");
  }

  @Test
  void handlesNullContentWithMedia() {
    var turns = List.of(new ChatTurn(ChatRole.USER, null, List.of(img())));

    var out = AbstractTextGenEngine.injectMediaMarkers(turns, M);

    assertThat(out.get(0).content()).isEqualTo(M + "\n");
  }

  @Test
  void markerCountMatchesAttachmentCountForMixedTurns() {
    var turns = List.of(
      new ChatTurn(ChatRole.USER, "a", List.of(img(), img())),
      new ChatTurn(ChatRole.ASSISTANT, "ok", List.of()),
      new ChatTurn(ChatRole.USER, "b", List.of(img()))
    );

    var out = AbstractTextGenEngine.injectMediaMarkers(turns, M);

    long markers = out
      .stream()
      .mapToLong(t -> t.content().split(M, -1).length - 1)
      .sum();
    long attachments = turns
      .stream()
      .mapToLong(t -> t.media().size())
      .sum();
    assertThat(markers).isEqualTo(attachments).isEqualTo(3);
  }

  @Test
  void nullTurnsReturnNull() {
    assertThat(AbstractTextGenEngine.injectMediaMarkers(null, M)).isNull();
  }
}
