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

package xyz.jonesdev.sonar.velocity;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import lombok.experimental.UtilityClass;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import xyz.jonesdev.sonar.api.Sonar;
import xyz.jonesdev.sonar.api.transfer.TransferTokenService;

import java.net.InetSocketAddress;

/**
 * XMine: moves a player to another proxy of the same network so that Sonar
 * there lets them in without the verification.
 * The receiving proxy needs the same transfer token secret and
 * {@code accepts-transfers = true} in velocity.toml.
 */
@UtilityClass
public class SonarVelocityTransfer {

  /**
   * Stores a transfer token in the player's cookie and transfers the player.
   * Without transfer tokens configured the player is transferred as is and
   * gets verified on the destination.
   *
   * @return whether the player carries a token
   * @throws IllegalArgumentException if the client is older than 1.20.5 and cannot be transferred
   */
  public boolean transfer(final @NotNull Player player, final @NotNull InetSocketAddress destination) {
    final boolean stored = storeToken(player);
    player.transferToHost(destination);
    return stored;
  }

  /**
   * Stores a transfer token in the player's cookie, for callers that transfer the player themselves.
   *
   * @return false if transfer tokens are disabled or the client is older than 1.20.5
   */
  public boolean storeToken(final @NotNull Player player) {
    final TransferTokenService tokens = Sonar.get().getTransferTokens();
    if (!tokens.isEnabled() || player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_20_5)) {
      return false;
    }
    player.storeCookie(Key.key(tokens.getCookieKey()),
      tokens.issue(player.getUsername(), player.getRemoteAddress().getAddress()));
    return true;
  }
}
