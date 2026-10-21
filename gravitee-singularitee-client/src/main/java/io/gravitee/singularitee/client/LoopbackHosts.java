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
package io.gravitee.singularitee.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Whether a host names this machine, so traffic to it never crosses the network.
 *
 * <p>Only {@code localhost} and IP literals count. Any other name is not loopback, whatever it
 * resolves to, and is never looked up: {@code 127.example.com} can point anywhere.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LoopbackHosts {

  private static final Pattern IPV4 = Pattern.compile(
    "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}"
  );

  private LoopbackHosts() {}

  /** Whether {@code host} is {@code localhost} or a loopback IPv4 or IPv6 literal. */
  public static boolean isLoopback(String host) {
    if (host == null) {
      return false;
    }
    String h = host.trim();
    if (h.startsWith("[") && h.endsWith("]")) {
      h = h.substring(1, h.length() - 1);
    }
    if (h.equalsIgnoreCase("localhost")) {
      return true;
    }
    if (IPV4.matcher(h).matches()) {
      return h.startsWith("127.");
    }
    if (h.indexOf(':') < 0) {
      return false;
    }
    // Bracketed, the string is parsed as an IPv6 literal only: an invalid one fails, never resolves.
    try {
      return InetAddress.getByName("[" + h + "]").isLoopbackAddress();
    } catch (UnknownHostException notALiteral) {
      return false;
    }
  }
}
