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
import xyz.jonesdev.sonar.api.config.SonarConfiguration;
import xyz.jonesdev.sonar.api.logger.LoggerWrapper;
import xyz.jonesdev.sonar.api.shared.SharedVerifiedStore;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Keeps verified players in Redis as {@code <prefix><fingerprint>} with the time of the
 * verification as the value, expiring after the same number of days as the database entries.
 * <br>
 * Reads and writes run on their own small thread pools, never on the event loop. Writes use
 * a single thread, so an add followed by a remove of the same player cannot be swapped.
 */
public final class RedisSharedVerifiedStore implements SharedVerifiedStore {
  private static final long ERROR_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
  private static final long STATISTICS_INTERVAL_MINUTES = 10;
  private static final int READ_THREADS = 4;
  private static final int READ_QUEUE = 256;
  private static final int WRITE_QUEUE = 1024;

  private final RedisBackend backend;
  private final String keyPrefix;
  private final long ttlSeconds;
  private final long timeoutMillis;
  private final LoggerWrapper logger;
  private final LongSupplier nanoClock;
  private final ExecutorService readers;
  private final ExecutorService writer;
  private final ScheduledExecutorService reporter;

  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();
  private final AtomicLong errors = new AtomicLong();
  private final AtomicLong suppressedErrors = new AtomicLong();
  // Zero means "nothing logged yet", so the first error is always logged
  private final AtomicLong lastErrorLog = new AtomicLong();
  private long reportedTotal;

  RedisSharedVerifiedStore(final @NotNull RedisBackend backend,
                           final @NotNull String keyPrefix,
                           final long ttlSeconds,
                           final long timeoutMillis,
                           final @NotNull LoggerWrapper logger,
                           final @NotNull LongSupplier nanoClock) {
    this.backend = backend;
    this.keyPrefix = keyPrefix;
    this.ttlSeconds = ttlSeconds;
    this.timeoutMillis = timeoutMillis;
    this.logger = logger;
    this.nanoClock = nanoClock;
    this.readers = new ThreadPoolExecutor(READ_THREADS, READ_THREADS, 0L, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(READ_QUEUE), namedDaemons("reader"));
    this.writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(WRITE_QUEUE), namedDaemons("writer"));
    this.reporter = Executors.newSingleThreadScheduledExecutor(namedDaemons("reporter"));
    reporter.scheduleWithFixedDelay(this::reportStatistics,
      STATISTICS_INTERVAL_MINUTES, STATISTICS_INTERVAL_MINUTES, TimeUnit.MINUTES);
  }

  /**
   * Any problem with the configuration disables the shared list instead of failing the start.
   * The values come only from the environment: the configuration holds the names of the variables.
   */
  public static @NotNull SharedVerifiedStore fromConfig(final @NotNull SonarConfiguration.SharedVerified config,
                                                        final int maximumAgeDays,
                                                        final @NotNull Function<String, String> environment,
                                                        final @NotNull LoggerWrapper logger) {
    if (!config.isEnabled()) {
      return DISABLED;
    }
    final String host = read(config.getHostEnv(), environment);
    if (host == null) {
      logger.error("Shared verified list is disabled: environment variable '{}' with the Redis host is not set.",
        config.getHostEnv());
      return DISABLED;
    }
    int port = 6379;
    final String portText = read(config.getPortEnv(), environment);
    if (portText != null) {
      try {
        port = Integer.parseInt(portText.trim());
        if (port < 1 || port > 65535) {
          throw new NumberFormatException();
        }
      } catch (NumberFormatException exception) {
        logger.error("Shared verified list is disabled: environment variable '{}' is not a valid port.",
          config.getPortEnv());
        return DISABLED;
      }
    }
    final String keyPrefix = config.getKeyPrefix();
    final RedisBackend backend;
    try {
      backend = new JedisRedisBackend(host, port,
        read(config.getUsernameEnv(), environment), read(config.getPasswordEnv(), environment),
        config.getTimeout());
    } catch (LinkageError error) {
      // The client expects SLF4J from the platform, as Velocity provides; others may lack it
      logger.error("Shared verified list is disabled: the Redis client cannot be loaded on this platform ({}).",
        error);
      return DISABLED;
    }
    logger.info("Shared verified list is enabled: Redis {}:{}, key prefix '{}', timeout {} ms, expiry {} days.",
      host, port, keyPrefix, config.getTimeout(), maximumAgeDays);
    return new RedisSharedVerifiedStore(backend, keyPrefix, TimeUnit.DAYS.toSeconds(maximumAgeDays),
      config.getTimeout(), logger, System::nanoTime);
  }

  private static @Nullable String read(final @Nullable String variable,
                                       final @NotNull Function<String, String> environment) {
    if (variable == null || variable.isEmpty()) {
      return null;
    }
    final String value = environment.apply(variable);
    return value == null || value.isEmpty() ? null : value;
  }

  private static @NotNull ThreadFactory namedDaemons(final @NotNull String role) {
    final AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      final Thread thread = new Thread(runnable, "sonar-shared-verified-" + role + "-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public @NotNull CompletableFuture<Boolean> contains(final @NotNull String fingerprint) {
    final CompletableFuture<Boolean> lookup = new CompletableFuture<Boolean>()
      // The time spent waiting in the queue and for the connection counts as well
      .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
    try {
      readers.execute(() -> {
        try {
          final boolean found = backend.get(keyPrefix + fingerprint) != null;
          // A lookup that has already timed out was counted as an error; its late answer is dropped
          lookup.complete(found);
        } catch (Throwable throwable) {
          lookup.completeExceptionally(throwable);
        }
      });
    } catch (RejectedExecutionException exception) {
      lookup.completeExceptionally(exception);
    }
    return lookup.handle((found, throwable) -> {
      if (throwable != null) {
        countError("lookup", throwable);
        return false;
      }
      // Counted here, before the caller sees the answer
      (found ? hits : misses).incrementAndGet();
      return found;
    });
  }

  @Override
  public void add(final @NotNull String fingerprint, final long timestamp) {
    write("add", () -> backend.set(keyPrefix + fingerprint, Long.toString(timestamp), ttlSeconds));
  }

  @Override
  public void remove(final @NotNull String fingerprint) {
    write("remove", () -> backend.delete(keyPrefix + fingerprint));
  }

  @Override
  public void removeAll() {
    write("clear", () -> {
      final long deleted = backend.deleteByPrefix(keyPrefix);
      logger.info("Removed {} entries of the shared verified list.", deleted);
    });
  }

  private void write(final @NotNull String what, final @NotNull Runnable action) {
    try {
      writer.execute(() -> {
        try {
          action.run();
        } catch (Throwable throwable) {
          countError(what, throwable);
        }
      });
    } catch (RejectedExecutionException exception) {
      countError(what, exception);
    }
  }

  private void countError(final @NotNull String what, final @NotNull Throwable throwable) {
    errors.incrementAndGet();
    // Redis being down means an error on every login: log it once in a while, not on each one
    final long now = nanoClock.getAsLong() | 1L;
    final long last = lastErrorLog.get();
    if ((last == 0L || now - last >= ERROR_LOG_INTERVAL_NANOS) && lastErrorLog.compareAndSet(last, now)) {
      final Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
        ? throwable.getCause() : throwable;
      logger.warn("Shared verified list ({}) failed: {} ({} more errors since the last message). "
          + "Players are verified as usual meanwhile.",
        what, cause, suppressedErrors.getAndSet(0));
    } else {
      suppressedErrors.incrementAndGet();
    }
  }

  public long getHits() {
    return hits.get();
  }

  public long getMisses() {
    return misses.get();
  }

  public long getErrors() {
    return errors.get();
  }

  private void reportStatistics() {
    final long total = hits.get() + misses.get() + errors.get();
    if (total != reportedTotal) {
      reportedTotal = total;
      logger.info("Shared verified list: {} hits, {} misses, {} errors or timeouts.",
        hits.get(), misses.get(), errors.get());
    }
  }

  @Override
  public void close() {
    reportStatistics();
    reporter.shutdownNow();
    readers.shutdownNow();
    // Let the queued writes finish, a removal must not be lost on a reload
    writer.shutdown();
    try {
      writer.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
    writer.shutdownNow();
    backend.close();
  }
}
