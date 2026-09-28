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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jonesdev.sonar.api.config.SonarConfiguration;
import xyz.jonesdev.sonar.api.logger.LoggerWrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Signed tokens that let a player skip the verification when one proxy of a
 * network moves them to another one (for example, before a restart).
 * <br>
 * The sending proxy puts a token into a cookie and transfers the player; the
 * receiving proxy asks for that cookie during login and lets the player in
 * without the verification if the token is valid. A token is a JWT signed with
 * HS256 by a secret all proxies of the network share. It is bound to the
 * username and the IP address and lives for a short time, so there is nothing
 * to store and nothing to look up.
 */
public final class TransferTokenService {
  private static final String AUDIENCE = "sonar-transfer";
  private static final String ALGORITHM = "HmacSHA256";
  // The header is fixed: a token with any other header is rejected before its
  // signature is looked at, so "alg": "none" and other algorithms never apply.
  private static final String HEADER = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
    .getBytes(StandardCharsets.UTF_8));
  // Cookies are limited to 5 KiB by the protocol; our tokens are ~250 bytes.
  private static final int MAX_TOKEN_LENGTH = 1024;
  private static final int MIN_SECRET_LENGTH = 32;
  // Proxies of one network are expected to have their clocks in sync, but not to the second.
  private static final long CLOCK_LEEWAY_SECONDS = 5;
  private static final Pattern COOKIE_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

  public static final TransferTokenService DISABLED = new TransferTokenService(
    Collections.emptyList(), "minecraft:disabled", 0, 0, Clock.systemUTC());

  private final List<SecretKeySpec> keys;
  @Getter
  private final String cookieKey;
  private final long lifetimeSeconds;
  @Getter
  private final int responseTimeout;
  private final Clock clock;

  /**
   * @param secrets the first one signs new tokens, every one of them is accepted;
   *                a second one exists for rotating the secret without downtime
   */
  public TransferTokenService(final @NotNull List<byte[]> secrets,
                              final @NotNull String cookieKey,
                              final long lifetimeSeconds,
                              final int responseTimeout,
                              final @NotNull Clock clock) {
    final List<SecretKeySpec> keys = new ArrayList<>(secrets.size());
    for (final byte[] secret : secrets) {
      keys.add(new SecretKeySpec(secret, ALGORITHM));
    }
    this.keys = Collections.unmodifiableList(keys);
    this.cookieKey = cookieKey;
    this.lifetimeSeconds = lifetimeSeconds;
    this.responseTimeout = responseTimeout;
    this.clock = clock;
  }

  /**
   * Any problem with the configuration disables the tokens instead of failing
   * the start: without them every transferred player is simply verified as usual.
   */
  public static @NotNull TransferTokenService fromConfig(final @NotNull SonarConfiguration.TransferToken config,
                                                         final @NotNull Function<String, String> environment,
                                                         final @NotNull LoggerWrapper logger) {
    if (!config.isEnabled()) {
      return DISABLED;
    }
    if (!COOKIE_KEY.matcher(config.getCookieKey()).matches()) {
      logger.error("Transfer tokens are disabled: cookie key '{}' is not a valid namespaced key.",
        config.getCookieKey());
      return DISABLED;
    }
    final byte[] current = readSecret(config.getSecretEnv(), environment);
    if (current == null) {
      logger.error("Transfer tokens are disabled: environment variable '{}' must hold at least {} bytes.",
        config.getSecretEnv(), MIN_SECRET_LENGTH);
      return DISABLED;
    }
    final List<byte[]> secrets = new ArrayList<>(2);
    secrets.add(current);
    if (!config.getPreviousSecretEnv().isEmpty() && environment.apply(config.getPreviousSecretEnv()) != null) {
      final byte[] previous = readSecret(config.getPreviousSecretEnv(), environment);
      if (previous == null) {
        logger.error("Previous transfer token secret is ignored: environment variable '{}' must hold at least {} bytes.",
          config.getPreviousSecretEnv(), MIN_SECRET_LENGTH);
      } else {
        secrets.add(previous);
      }
    }
    logger.info("Transfer tokens are enabled with {} secret(s), cookie {}.", secrets.size(), config.getCookieKey());
    return new TransferTokenService(secrets, config.getCookieKey(), config.getLifetimeSeconds(),
      config.getResponseTimeout(), Clock.systemUTC());
  }

  private static byte @Nullable [] readSecret(final @NotNull String variable,
                                              final @NotNull Function<String, String> environment) {
    if (variable.isEmpty()) {
      return null;
    }
    final String value = environment.apply(variable);
    if (value == null) {
      return null;
    }
    final byte[] secret = value.getBytes(StandardCharsets.UTF_8);
    return secret.length < MIN_SECRET_LENGTH ? null : secret;
  }

  public boolean isEnabled() {
    return !keys.isEmpty();
  }

  /**
   * Creates a token for the player that is about to be transferred.
   *
   * @throws IllegalStateException if transfer tokens are disabled
   */
  public byte @NotNull [] issue(final @NotNull String username, final @NotNull InetAddress address) {
    if (!isEnabled()) {
      throw new IllegalStateException("Transfer tokens are disabled");
    }
    final long now = clock.instant().getEpochSecond();
    final JsonObject payload = new JsonObject();
    payload.addProperty("aud", AUDIENCE);
    payload.addProperty("sub", username);
    payload.addProperty("ip", address.getHostAddress());
    payload.addProperty("iat", now);
    payload.addProperty("exp", now + lifetimeSeconds);
    final String signingInput = HEADER + "." + encode(payload.toString().getBytes(StandardCharsets.UTF_8));
    final String token = signingInput + "." + encode(sign(keys.get(0), signingInput));
    return token.getBytes(StandardCharsets.US_ASCII);
  }

  public @NotNull Verdict verify(final byte @Nullable [] token,
                                 final @NotNull String username,
                                 final @NotNull InetAddress address) {
    if (token == null || token.length == 0) {
      return Verdict.MISSING;
    }
    if (!isEnabled() || token.length > MAX_TOKEN_LENGTH) {
      return Verdict.MALFORMED;
    }
    final String text = new String(token, StandardCharsets.US_ASCII);
    final int first = text.indexOf('.');
    final int second = first < 0 ? -1 : text.indexOf('.', first + 1);
    if (second < 0 || text.indexOf('.', second + 1) >= 0 || !text.substring(0, first).equals(HEADER)) {
      return Verdict.MALFORMED;
    }

    final String signingInput = text.substring(0, second);
    final byte[] signature;
    try {
      signature = Base64.getUrlDecoder().decode(text.substring(second + 1));
    } catch (IllegalArgumentException exception) {
      return Verdict.MALFORMED;
    }
    boolean signed = false;
    for (final SecretKeySpec key : keys) {
      // Constant time: the comparison must not tell how much of a forged signature is right
      signed |= MessageDigest.isEqual(sign(key, signingInput), signature);
    }
    if (!signed) {
      return Verdict.BAD_SIGNATURE;
    }

    final String audience, subject, ip;
    final long issuedAt, expiresAt;
    try {
      final JsonObject payload = JsonParser.parseString(new String(
        Base64.getUrlDecoder().decode(text.substring(first + 1, second)), StandardCharsets.UTF_8)).getAsJsonObject();
      audience = payload.get("aud").getAsString();
      subject = payload.get("sub").getAsString();
      ip = payload.get("ip").getAsString();
      issuedAt = payload.get("iat").getAsLong();
      expiresAt = payload.get("exp").getAsLong();
    } catch (RuntimeException exception) {
      return Verdict.MALFORMED;
    }
    if (!AUDIENCE.equals(audience)) {
      return Verdict.MALFORMED;
    }
    final long now = clock.instant().getEpochSecond();
    if (expiresAt + CLOCK_LEEWAY_SECONDS < now || issuedAt - CLOCK_LEEWAY_SECONDS > now) {
      return Verdict.EXPIRED;
    }
    if (!subject.equals(username)) {
      return Verdict.OTHER_PLAYER;
    }
    if (!ip.equals(address.getHostAddress())) {
      return Verdict.OTHER_ADDRESS;
    }
    return Verdict.VALID;
  }

  private static byte @NotNull [] sign(final @NotNull SecretKeySpec key, final @NotNull String signingInput) {
    try {
      final Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException exception) {
      // HmacSHA256 is mandatory for every Java platform
      throw new IllegalStateException(exception);
    }
  }

  private static @NotNull String encode(final byte @NotNull [] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public enum Verdict {
    VALID,
    // The player sent no token: transferred from a server outside the network
    MISSING,
    MALFORMED,
    BAD_SIGNATURE,
    EXPIRED,
    OTHER_PLAYER,
    OTHER_ADDRESS
  }
}
