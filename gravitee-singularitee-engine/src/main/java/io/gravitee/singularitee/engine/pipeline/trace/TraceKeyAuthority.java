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
package io.gravitee.singularitee.engine.pipeline.trace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Decides whether a request may see a step trace: only a caller presenting the key whose SHA-256
 * the control plane announced to this node. Without an announcement nothing is traced.
 */
public final class TraceKeyAuthority {

  private volatile byte[] expected;

  /** Records the announced hash, hex encoded; a blank value withdraws it. */
  public void expect(String sha256Hex) {
    if (sha256Hex == null || sha256Hex.isBlank()) {
      expected = null;
      return;
    }
    try {
      expected = HexFormat.of().parseHex(sha256Hex.strip());
    } catch (IllegalArgumentException e) {
      expected = null;
    }
  }

  public boolean allows(String presentedKey) {
    var want = expected;
    if (want == null || presentedKey == null || presentedKey.isBlank()) {
      return false;
    }
    return MessageDigest.isEqual(want, sha256(presentedKey));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
