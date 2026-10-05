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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RedisSharedVerifiedStoreTest {
  private static final String PREFIX = "test:verified:";
  private static final long TIMEOUT = 300;

  private final FakeBackend backend = new FakeBackend();
  private final RecordingLogger logger = new RecordingLogger();
  private final AtomicLong nanos = new AtomicLong(1);
  private RedisSharedVerifiedStore store;

  @BeforeEach
  void setUp() {
    store = new RedisSharedVerifiedStore(backend, PREFIX, 5 * 86400L, TIMEOUT, logger, nanos::get);
  }

  @AfterEach
  void tearDown() {
    backend.gate.countDown();
    store.close();
  }

  private boolean contains(final String fingerprint) throws Exception {
    return store.contains(fingerprint).get(5, TimeUnit.SECONDS);
  }

  private void drainWrites() throws Exception {
    // The single writer thread runs the tasks in order, so a marker task is the last one
    final CountDownLatch done = new CountDownLatch(1);
    backend.afterWrites = done::countDown;
    store.remove("marker");
    assertTrue(done.await(5, TimeUnit.SECONDS));
  }

  @Test
  void hitIsFoundAndCounted() throws Exception {
    backend.data.put(PREFIX + "abc", "1");
    assertTrue(contains("abc"));
    assertEquals(1, store.getHits());
    assertEquals(0, store.getMisses());
    assertEquals(0, store.getErrors());
  }

  @Test
  void missIsNotFoundAndCounted() throws Exception {
    // A key of somebody else's prefix is not ours
    backend.data.put("other:verified:abc", "1");
    assertFalse(contains("abc"));
    assertEquals(0, store.getHits());
    assertEquals(1, store.getMisses());
  }

  @Test
  void backendErrorMeansNotFound() throws Exception {
    backend.failure = new IllegalStateException("connection refused");
    assertFalse(contains("abc"));
    assertEquals(1, store.getErrors());
    assertEquals(0, store.getMisses());
  }

  @Test
  void slowBackendTimesOutAndMeansNotFound() throws Exception {
    backend.data.put(PREFIX + "abc", "1");
    backend.gate = new CountDownLatch(1);
    final long start = System.nanoTime();
    assertFalse(contains("abc"));
    final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(elapsed < TIMEOUT * 4, "took " + elapsed + " ms");
    assertEquals(1, store.getErrors());
    backend.gate.countDown();
    // The late answer must not turn into a hit
    Thread.sleep(100);
    assertEquals(0, store.getHits());
  }

  @Test
  void overflowOfTheQueueMeansNotFound() throws Exception {
    backend.gate = new CountDownLatch(1);
    final List<CompletableFuture<Boolean>> lookups = new java.util.ArrayList<>();
    for (int i = 0; i < 4 + 256 + 50; i++) {
      lookups.add(store.contains("p" + i));
    }
    for (final CompletableFuture<Boolean> lookup : lookups) {
      assertFalse(lookup.get(5, TimeUnit.SECONDS));
    }
    assertTrue(store.getErrors() >= 50);
  }

  @Test
  void addWritesKeyWithPrefixValueAndExpiry() throws Exception {
    store.add("abc", 1234L);
    drainWrites();
    assertEquals("1234", backend.data.get(PREFIX + "abc"));
    assertEquals(5 * 86400L, backend.ttls.get(PREFIX + "abc"));
  }

  @Test
  void removeDeletesOnlyThatKey() throws Exception {
    backend.data.put(PREFIX + "abc", "1");
    backend.data.put(PREFIX + "def", "1");
    store.remove("abc");
    drainWrites();
    assertFalse(backend.data.containsKey(PREFIX + "abc"));
    assertTrue(backend.data.containsKey(PREFIX + "def"));
  }

  @Test
  void removeAllAsksForOurPrefixOnly() throws Exception {
    store.removeAll();
    drainWrites();
    assertEquals(List.of(PREFIX), backend.prefixDeletes);
  }

  @Test
  void addFollowedByRemoveKeepsOrder() throws Exception {
    for (int i = 0; i < 200; i++) {
      store.add("abc", i);
      store.remove("abc");
    }
    drainWrites();
    assertFalse(backend.data.containsKey(PREFIX + "abc"));
  }

  @Test
  void writeErrorIsLoggedNotThrown() throws Exception {
    backend.failure = new IllegalStateException("down");
    store.add("abc", 1L);
    drainWrites();
    assertEquals(2, store.getErrors()); // the add and the marker removal
    assertEquals(1, logger.warnings.size());
  }

  @Test
  void errorsAreLoggedOncePerInterval() throws Exception {
    backend.failure = new IllegalStateException("down");
    for (int i = 0; i < 50; i++) {
      contains("p" + i);
    }
    assertEquals(50, store.getErrors());
    assertEquals(1, logger.warnings.size());
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(31));
    contains("later");
    assertEquals(2, logger.warnings.size());
    assertTrue(logger.warnings.get(1).contains("49 more errors"), logger.warnings.get(1));
  }

  @Test
  void disabledStoreKnowsNobody() throws Exception {
    assertFalse(xyz.jonesdev.sonar.api.shared.SharedVerifiedStore.DISABLED.isEnabled());
    assertFalse(xyz.jonesdev.sonar.api.shared.SharedVerifiedStore.DISABLED.contains("abc").get());
  }

  private static final class FakeBackend implements RedisBackend {
    final Map<String, String> data = new ConcurrentHashMap<>();
    final Map<String, Long> ttls = new ConcurrentHashMap<>();
    final List<String> prefixDeletes = new CopyOnWriteArrayList<>();
    volatile RuntimeException failure;
    volatile CountDownLatch gate = new CountDownLatch(0);
    volatile Runnable afterWrites = () -> {
    };

    private void enter() {
      try {
        gate.await();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(exception);
      }
      if (failure != null) {
        throw failure;
      }
    }

    @Override
    public @Nullable String get(final @NotNull String key) {
      enter();
      return data.get(key);
    }

    @Override
    public void set(final @NotNull String key, final @NotNull String value, final long ttlSeconds) {
      enter();
      data.put(key, value);
      ttls.put(key, ttlSeconds);
    }

    @Override
    public void delete(final @NotNull String key) {
      try {
        enter();
        data.remove(key);
      } finally {
        if (key.endsWith("marker")) {
          afterWrites.run();
        }
      }
    }

    @Override
    public long deleteByPrefix(final @NotNull String prefix) {
      enter();
      prefixDeletes.add(prefix);
      return 0;
    }

    @Override
    public void close() {
    }
  }
}
