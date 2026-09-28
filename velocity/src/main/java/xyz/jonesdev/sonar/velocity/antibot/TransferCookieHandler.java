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

package xyz.jonesdev.sonar.velocity.antibot;

import com.velocitypowered.proxy.protocol.packet.ServerboundCookieResponsePacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * XMine: sits in the pipeline between the cookie request and the client's answer.
 * Every path - the answer, the timeout, anything else from the client, a closed
 * channel - ends in {@link #complete}, which runs the callback exactly once.
 * All of them run in the channel's event loop, so the flag needs no synchronization.
 */
final class TransferCookieHandler extends ChannelInboundHandlerAdapter {
  private final Key key;
  private final Consumer<byte @Nullable []> callback;
  private @Nullable ScheduledFuture<?> timeout;
  private boolean completed;

  TransferCookieHandler(final @NotNull Key key, final @NotNull Consumer<byte @Nullable []> callback) {
    this.key = key;
    this.callback = callback;
  }

  void setTimeout(final @NotNull ScheduledFuture<?> timeout) {
    this.timeout = timeout;
  }

  @Override
  public void channelRead(final @NotNull ChannelHandlerContext ctx, final Object msg) throws Exception {
    if (msg instanceof ServerboundCookieResponsePacket response && key.equals(response.getKey())) {
      // Our own request: Velocity never asked for it and must not see the answer
      complete(ctx, response.getPayload());
      return;
    }
    // A client waiting for the login to finish sends nothing else;
    // whatever this is, the token is not coming
    complete(ctx, null);
    ctx.fireChannelRead(msg);
  }

  @Override
  public void channelInactive(final @NotNull ChannelHandlerContext ctx) throws Exception {
    // The callback checks the channel and stops there
    complete(ctx, null);
    ctx.fireChannelInactive();
  }

  void complete(final @NotNull ChannelHandlerContext ctx, final byte @Nullable [] payload) {
    if (completed) {
      return;
    }
    completed = true;
    if (timeout != null) {
      timeout.cancel(false);
    }
    if (ctx.pipeline().context(this) != null) {
      ctx.pipeline().remove(this);
    }
    callback.accept(payload);
  }
}
