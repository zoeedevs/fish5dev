package com.fish5.mixin;

import com.fish5.features.impl.opsec.ProxyManager;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Native proxy support — inserts a SOCKS5/HTTP proxy handler at the front of the connection pipeline.
 *
 * <p>{@code Connection$1} is the anonymous {@link io.netty.channel.ChannelInitializer} that
 * {@code Connection.connect} hands to the Netty bootstrap; its {@code initChannel} builds the packet
 * pipeline. Adding the proxy handler first (before serialization handlers) makes Netty establish the
 * connection through the proxy to the real server address the bootstrap dials. When {@link ProxyManager}
 * returns no handler (proxying off or suspended for mod compatibility), this is a no-op and the
 * connection is direct, exactly as vanilla.
 */
@Mixin(targets = "net.minecraft.network.Connection$1")
public class ConnectionProxyMixin {

    @Inject(method = "initChannel(Lio/netty/channel/Channel;)V", at = @At("HEAD"))
    private void fish5$insertProxy(Channel channel, CallbackInfo ci) {
        ChannelHandler handler = ProxyManager.INSTANCE.createHandler();
        if (handler != null) {
            channel.pipeline().addFirst("fish5_proxy", handler);
        }
    }
}
