/*
 * Copyright (C) 2026 Sonar Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package xyz.jonesdev.sonar.api.transfer;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static xyz.jonesdev.sonar.api.transfer.TransferTokenService.Verdict.*;

class TransferTokenServiceTest {
  private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OTHER_SECRET = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
  private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
  private static final long LIFETIME = 60;
  private static final InetAddress ADDRESS = address("203.0.113.7");

  private static InetAddress address(final String ip) {
    try {
      return InetAddress.getByName(ip);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static TransferTokenService service(final List<byte[]> secrets, final Instant now) {
    return new TransferTokenService(secrets, "sonar:transfer_token", LIFETIME, 5000,
      Clock.fixed(now, ZoneOffset.UTC));
  }

  private static TransferTokenService service(final Instant now) {
    return service(List.of(SECRET), now);
  }

  private static String[] parts(final byte[] token) {
    return new String(token, StandardCharsets.US_ASCII).split("\\.");
  }

  private static byte[] join(final String... parts) {
    return String.join(".", parts).getBytes(StandardCharsets.US_ASCII);
  }

  @Test
  void issuedTokenIsValidForTheSamePlayer() {
    final byte[] token = service(NOW).issue("Steve", ADDRESS);
    assertEquals(VALID, service(NOW.plusSeconds(10)).verify(token, "Steve", ADDRESS));
  }

  @Test
  void tokenIsBoundToUsernameAndAddress() {
    final byte[] token = service(NOW).issue("Steve", ADDRESS);
    assertEquals(OTHER_PLAYER, service(NOW).verify(token, "Alex", ADDRESS));
    assertEquals(OTHER_ADDRESS, service(NOW).verify(token, "Steve", address("203.0.113.8")));
  }

  @Test
  void tokenExpiresAfterLifetimeAndLeeway() {
    final byte[] token = service(NOW).issue("Steve", ADDRESS);
    assertEquals(VALID, service(NOW.plusSeconds(LIFETIME + 5)).verify(token, "Steve", ADDRESS));
    assertEquals(EXPIRED, service(NOW.plusSeconds(LIFETIME + 6)).verify(token, "Steve", ADDRESS));
  }

  @Test
  void tokenFromTheFutureIsRejected() {
    final byte[] token = service(NOW.plusSeconds(30)).issue("Steve", ADDRESS);
    assertEquals(EXPIRED, service(NOW).verify(token, "Steve", ADDRESS));
  }

  @Test
  void tokenSignedWithAnotherSecretIsRejected() {
    final byte[] token = service(List.of(OTHER_SECRET), NOW).issue("Steve", ADDRESS);
    assertEquals(BAD_SIGNATURE, service(NOW).verify(token, "Steve", ADDRESS));
  }

  @Test
  void previousSecretIsAcceptedDuringRotation() {
    final byte[] token = service(List.of(OTHER_SECRET), NOW).issue("Steve", ADDRESS);
    assertEquals(VALID, service(List.of(SECRET, OTHER_SECRET), NOW).verify(token, "Steve", ADDRESS));
  }

  @Test
  void changedPayloadBreaksTheSignature() {
    final String[] parts = parts(service(NOW).issue("Steve", ADDRESS));
    final String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
      .replace("Steve", "Alex");
    final String forged = Base64.getUrlEncoder().withoutPadding()
      .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    assertEquals(BAD_SIGNATURE, service(NOW).verify(join(parts[0], forged, parts[2]), "Alex", ADDRESS));
  }

  @Test
  void unsignedTokenIsRejected() {
    final String[] parts = parts(service(NOW).issue("Steve", ADDRESS));
    final String none = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    assertEquals(MALFORMED, service(NOW).verify(join(none, parts[1], ""), "Steve", ADDRESS));
    assertEquals(MALFORMED, service(NOW).verify(join(none, parts[1]), "Steve", ADDRESS));
  }

  @Test
  void garbageIsMalformedAndNothingIsMissing() {
    final TransferTokenService service = service(NOW);
    assertEquals(MISSING, service.verify(null, "Steve", ADDRESS));
    assertEquals(MISSING, service.verify(new byte[0], "Steve", ADDRESS));
    assertEquals(MALFORMED, service.verify("not a token".getBytes(StandardCharsets.US_ASCII), "Steve", ADDRESS));
    assertEquals(MALFORMED, service.verify(new byte[2048], "Steve", ADDRESS));
  }

  @Test
  void disabledServiceNeitherIssuesNorAccepts() {
    final byte[] token = service(NOW).issue("Steve", ADDRESS);
    final TransferTokenService disabled = service(Collections.emptyList(), NOW);
    assertThrows(IllegalStateException.class, () -> disabled.issue("Steve", ADDRESS));
    assertEquals(MALFORMED, disabled.verify(token, "Steve", ADDRESS));
  }
}
