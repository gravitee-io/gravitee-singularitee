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
package io.gravitee.singularitee.inference.api.textgen;

/**
 * Media types for image and audio content.
 * Follows IANA media type registry conventions.
 *
 * <p>Deliberately limited to what the engines can decode. Images are decoded by
 * llama.cpp's vendored stb_image, which supports JPEG, PNG, GIF and BMP but has
 * no WebP or TIFF decoder. Audio is decoded by {@code javax.sound.sampled},
 * whose built-in readers cover WAV (plus AU/AIFF) and no compressed format
 * without an extra SPI provider.
 *
 * <p>Do not add a constant here without a decoder behind it: an unsupported
 * payload is dropped without an error, and the model then answers from the text
 * alone — an empty or plausible-but-blind response rather than a failure.
 */
public enum MediaType {
  // Image types — stb_image
  IMAGE_JPEG("image/jpeg"),
  IMAGE_PNG("image/png"),
  IMAGE_GIF("image/gif"),
  IMAGE_BMP("image/bmp"),

  // Audio types — javax.sound.sampled
  AUDIO_WAV("audio/wav"),

  // Generic binary
  APPLICATION_OCTET_STREAM("application/octet-stream");

  private final String value;

  MediaType(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static MediaType fromString(String value) {
    for (MediaType type : MediaType.values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    return APPLICATION_OCTET_STREAM;
  }
}
