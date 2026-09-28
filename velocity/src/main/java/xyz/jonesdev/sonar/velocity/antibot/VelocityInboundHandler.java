/*
 * Copyright (C) 2025 Sonar Contributors
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

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.protocol.packet.ClientboundCookieRequestPacket;
import com.velocitypowered.proxy.protocol.packet.HandshakePacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket;
import io.netty.channel.ChannelHandlerContext;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jonesdev.sonar.common.InboundHandlerAdapter;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.velocitypowered.proxy.network.Connections.MINECRAFT_DECODER;
import static xyz.jonesdev.sonar.api.antibot.ChannelPipelines.SONAR_TRANSFER_COOKIE;
import static xyz.jonesdev.sonar.common.protocol.packets.handshake.HandshakePacket.STATUS;
import static xyz.jonesdev.sonar.common.protocol.packets.handshake.HandshakePacket.TRANSFER;

final class VelocityInboundHandler extends InboundHandlerAdapter {

  @Override
  public void channelRead(final @NotNull ChannelHandlerContext ctx, final Object msg) throws Exception {
    if (msg instanceof HandshakePacket handshake) {
      // We don't care about server pings; remove the handler
      if (handshake.getNextStatus() == STATUS) {
        ctx.pipeline().remove(this);
      } else {
        transferIntent = handshake.getNextStatus() == TRANSFER;
        handleHandshake(ctx, handshake.getServerAddress(), handshake.getProtocolVersion().getProtocol());
      }
    } else if (msg instanceof ServerLoginPacket serverLogin) {
      // Deject this pipeline and let Sonar process the login packet
      ctx.pipeline().remove(this);
      // Make sure to use the potentially modified, original IP
      final MinecraftConnection minecraftConnection = ctx.pipeline().get(MinecraftConnection.class);
      final InetSocketAddress socketAddress = (InetSocketAddress) minecraftConnection.getRemoteAddress();
      handleLogin(ctx, () -> ctx.fireChannelRead(msg), serverLogin.getUsername(), socketAddress);
      return;
    }
    ctx.fireChannelRead(msg);
  }

  @Override
  protected boolean requestTransferToken(final @NotNull ChannelHandlerContext ctx,
                                         final @NotNull String cookieKey,
                                         final int timeoutMillis,
                                         final @NotNull Consumer<byte @Nullable []> callback) {
    // This handler has already left the pipeline, the cookie is caught by its own one
    final Key key = Key.key(cookieKey);
    final TransferCookieHandler handler = new TransferCookieHandler(key, callback);
    ctx.pipeline().addAfter(MINECRAFT_DECODER, SONAR_TRANSFER_COOKIE, handler);
    final ChannelHandlerContext handlerContext = ctx.pipeline().context(handler);
    handler.setTimeout(ctx.channel().eventLoop().schedule(
      () -> handler.complete(handlerContext, null), timeoutMillis, TimeUnit.MILLISECONDS));
    ctx.channel().writeAndFlush(new ClientboundCookieRequestPacket(key));
    return true;
  }
}
