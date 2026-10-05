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

package xyz.jonesdev.sonar.api.shared;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * A list of verified players shared by all proxies of a network.
 * <br>
 * The local cache of {@link xyz.jonesdev.sonar.api.database.controller.VerifiedPlayerController}
 * stays the first thing looked at; this store is asked only when the local cache misses.
 * It is an optimization, never a requirement: whatever goes wrong, a player is simply
 * verified as usual, and no method here blocks or throws.
 */
public interface SharedVerifiedStore {
  /**
   * Used while the feature is disabled: knows nobody and stores nothing.
   */
  SharedVerifiedStore DISABLED = new SharedVerifiedStore() {
    @Override
    public boolean isEnabled() {
      return false;
    }

    @Override
    public @NotNull CompletableFuture<Boolean> contains(final @NotNull String fingerprint) {
      return CompletableFuture.completedFuture(false);
    }

    @Override
    public void add(final @NotNull String fingerprint, final long timestamp) {
    }

    @Override
    public void remove(final @NotNull String fingerprint) {
    }

    @Override
    public void removeAll() {
    }

    @Override
    public void close() {
    }
  };

  boolean isEnabled();

  /**
   * Asks whether the player with this fingerprint has been verified on any proxy.
   *
   * @return A future that never completes exceptionally: it holds {@code false}
   * on a miss, an error or a timeout. It may complete on any thread.
   */
  @NotNull CompletableFuture<Boolean> contains(final @NotNull String fingerprint);

  /**
   * Remembers a verification asynchronously. Errors are logged, never thrown.
   */
  void add(final @NotNull String fingerprint, final long timestamp);

  /**
   * Forgets one verification asynchronously.
   */
  void remove(final @NotNull String fingerprint);

  /**
   * Forgets every verification of this network (and only of this network) asynchronously.
   */
  void removeAll();

  void close();
}
