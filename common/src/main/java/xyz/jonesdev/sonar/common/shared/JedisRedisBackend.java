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
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Jedis does not open a connection when the pool is created, so a proxy starts
 * without Redis; connections are made on demand and made again after a failure.
 */
final class JedisRedisBackend implements RedisBackend {
  private static final int SCAN_BATCH = 500;
  private final JedisPool pool;

  JedisRedisBackend(final @NotNull String host,
                    final int port,
                    final @Nullable String username,
                    final @Nullable String password,
                    final int timeoutMillis) {
    final JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxTotal(8);
    poolConfig.setMaxIdle(8);
    poolConfig.setMinIdle(0);
    poolConfig.setMaxWait(java.time.Duration.ofMillis(timeoutMillis));
    // A connection that died while idle (Redis restarted) is replaced instead of failing a login
    poolConfig.setTestOnBorrow(true);
    final DefaultJedisClientConfig.Builder client = DefaultJedisClientConfig.builder()
      .connectionTimeoutMillis(timeoutMillis)
      .socketTimeoutMillis(timeoutMillis)
      .clientName("sonar-shared-verified");
    if (password != null && !password.isEmpty()) {
      if (username != null && !username.isEmpty()) {
        client.user(username);
      }
      client.password(password);
    }
    this.pool = new JedisPool(poolConfig, new HostAndPort(host, port), client.build());
  }

  @Override
  public @Nullable String get(final @NotNull String key) {
    try (final Jedis jedis = pool.getResource()) {
      return jedis.get(key);
    }
  }

  @Override
  public void set(final @NotNull String key, final @NotNull String value, final long ttlSeconds) {
    try (final Jedis jedis = pool.getResource()) {
      jedis.set(key, value, SetParams.setParams().ex(ttlSeconds));
    }
  }

  @Override
  public void delete(final @NotNull String key) {
    try (final Jedis jedis = pool.getResource()) {
      jedis.unlink(key);
    }
  }

  @Override
  public long deleteByPrefix(final @NotNull String prefix) {
    final ScanParams params = new ScanParams().match(escapeGlob(prefix) + "*").count(SCAN_BATCH);
    long deleted = 0;
    try (final Jedis jedis = pool.getResource()) {
      String cursor = ScanParams.SCAN_POINTER_START;
      do {
        final ScanResult<String> result = jedis.scan(cursor, params);
        cursor = result.getCursor();
        final List<String> keys = new ArrayList<>(result.getResult().size());
        for (final String key : result.getResult()) {
          // The pattern is only a hint for the server, never the safety net
          if (key.startsWith(prefix)) {
            keys.add(key);
          }
        }
        if (!keys.isEmpty()) {
          deleted += jedis.unlink(keys.toArray(new String[0]));
        }
      } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
    }
    return deleted;
  }

  @Override
  public void close() {
    pool.close();
  }

  static @NotNull String escapeGlob(final @NotNull String text) {
    final StringBuilder builder = new StringBuilder(text.length() + 4);
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
        builder.append('\\');
      }
      builder.append(c);
    }
    return builder.toString();
  }
}
