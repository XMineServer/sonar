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

package xyz.jonesdev.sonar.common.shared;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs against a real Redis in a container; skipped where Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisIntegrationTest {
  private static final String PASSWORD = "secret";

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
    .withCommand("redis-server", "--requirepass", PASSWORD)
    .withExposedPorts(6379);

  private final RecordingLogger logger = new RecordingLogger();

  private RedisSharedVerifiedStore store(final String prefix, final String password, final int port,
                                         final int timeout) {
    return new RedisSharedVerifiedStore(
      new JedisRedisBackend(REDIS.getHost(), port, null, password, timeout),
      prefix, 5 * 86400L, timeout, logger, System::nanoTime);
  }

  private RedisSharedVerifiedStore store(final String prefix) {
    return store(prefix, PASSWORD, REDIS.getMappedPort(6379), 1000);
  }

  private static void awaitWrites(final RedisSharedVerifiedStore store) throws Exception {
    // Writes are asynchronous: a read of the same key in the queue behind them would race,
    // so wait on the result instead
    Thread.sleep(300);
  }

  private static Jedis admin() {
    final Jedis jedis = new Jedis(REDIS.getHost(), REDIS.getMappedPort(6379));
    jedis.auth(PASSWORD);
    return jedis;
  }

  @BeforeEach
  void flush() {
    try (final Jedis jedis = admin()) {
      jedis.flushAll();
    }
  }

  @Test
  void anotherProxyFindsWhatOneProxyAdded() throws Exception {
    final RedisSharedVerifiedStore first = store("sonar:verified:");
    final RedisSharedVerifiedStore second = store("sonar:verified:");
    try {
      assertFalse(second.contains("abc").get(5, TimeUnit.SECONDS));
      first.add("abc", 1234L);
      awaitWrites(first);
      assertTrue(second.contains("abc").get(5, TimeUnit.SECONDS));
      assertEquals(1, second.getHits());
      assertEquals(1, second.getMisses());
      try (final Jedis jedis = admin()) {
        assertEquals("1234", jedis.get("sonar:verified:abc"));
        final long ttl = jedis.ttl("sonar:verified:abc");
        assertTrue(ttl > 4 * 86400L && ttl <= 5 * 86400L, "ttl " + ttl);
      }
      first.remove("abc");
      awaitWrites(first);
      assertFalse(second.contains("abc").get(5, TimeUnit.SECONDS));
    } finally {
      first.close();
      second.close();
    }
  }

  @Test
  void removeAllTouchesOnlyOurPrefix() throws Exception {
    try (final Jedis jedis = admin()) {
      for (int i = 0; i < 1200; i++) {
        jedis.set("net-a:" + i, "1");
        jedis.set("net-b:" + i, "1");
      }
      jedis.set("unrelated", "1");
      // A glob-looking prefix must not match its neighbours
      jedis.set("net-*:x", "1");
    }
    final RedisSharedVerifiedStore store = store("net-a:");
    try {
      store.removeAll();
      awaitWrites(store);
    } finally {
      store.close();
    }
    try (final Jedis jedis = admin()) {
      assertEquals(0, jedis.keys("net-a:*").size());
      assertEquals(1200, jedis.keys("net-b:*").size());
      assertEquals(Set.of("unrelated", "net-*:x"),
        jedis.keys("[un]*").stream().filter(key -> !key.startsWith("net-b:") && !key.startsWith("net-a:"))
          .collect(java.util.stream.Collectors.toSet()));
    }
  }

  @Test
  void globCharactersInThePrefixAreLiteral() throws Exception {
    try (final Jedis jedis = admin()) {
      jedis.set("net-*:1", "1");
      jedis.set("net-b:1", "1");
    }
    final RedisSharedVerifiedStore store = store("net-*:");
    try {
      store.removeAll();
      awaitWrites(store);
    } finally {
      store.close();
    }
    try (final Jedis jedis = admin()) {
      assertFalse(jedis.exists("net-*:1"));
      assertTrue(jedis.exists("net-b:1"));
    }
  }

  @Test
  void wrongPasswordMeansVerifyAsUsual() throws Exception {
    final RedisSharedVerifiedStore store = store("sonar:verified:", "wrong", REDIS.getMappedPort(6379), 1000);
    try {
      assertFalse(store.contains("abc").get(5, TimeUnit.SECONDS));
      assertEquals(1, store.getErrors());
      assertEquals(1, logger.warnings.size());
    } finally {
      store.close();
    }
  }

  @Test
  void unreachableRedisDoesNotStopTheStartAndMeansVerifyAsUsual() throws Exception {
    // Nothing listens on port 1: creating the store must not touch the network
    final long start = System.nanoTime();
    final RedisSharedVerifiedStore store = store("sonar:verified:", PASSWORD, 1, 300);
    try {
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 250);
      final long lookupStart = System.nanoTime();
      assertFalse(store.contains("abc").get(5, TimeUnit.SECONDS));
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lookupStart) < 1000);
      store.add("abc", 1L);
    } finally {
      store.close();
    }
  }

  @Test
  void recoversAfterRedisWasDown() throws Exception {
    final RedisSharedVerifiedStore store = store("sonar:verified:");
    try {
      store.add("abc", 1L);
      awaitWrites(store);
      assertTrue(store.contains("abc").get(5, TimeUnit.SECONDS));
      // Kill every connection of the pool from the server side
      try (final Jedis jedis = admin()) {
        jedis.clientKill(new redis.clients.jedis.params.ClientKillParams()
          .type(redis.clients.jedis.args.ClientType.NORMAL).skipMe(redis.clients.jedis.params.ClientKillParams.SkipMe.YES));
      }
      assertTrue(store.contains("abc").get(5, TimeUnit.SECONDS));
    } finally {
      store.close();
    }
  }
}
