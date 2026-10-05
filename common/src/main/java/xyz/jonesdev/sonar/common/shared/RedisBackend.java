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

/**
 * The few Redis commands the shared list needs. Every method blocks and may throw:
 * {@link RedisSharedVerifiedStore} runs them off the event loop and treats any failure as a miss.
 */
interface RedisBackend extends AutoCloseable {
  @Nullable String get(final @NotNull String key);

  void set(final @NotNull String key, final @NotNull String value, final long ttlSeconds);

  void delete(final @NotNull String key);

  /**
   * Deletes every key that starts with the prefix, using SCAN so the server is never blocked.
   *
   * @return The number of deleted keys.
   */
  long deleteByPrefix(final @NotNull String prefix);

  @Override
  void close();
}
