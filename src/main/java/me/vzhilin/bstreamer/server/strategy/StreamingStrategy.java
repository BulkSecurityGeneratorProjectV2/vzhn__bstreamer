package me.vzhilin.bstreamer.server.strategy;

import io.netty.channel.ChannelHandlerContext;
import me.vzhilin.bstreamer.server.streaming.file.MediaPacketSourceDescription;

public interface StreamingStrategy {
    void attachContext(ChannelHandlerContext context);
    void detachContext(ChannelHandlerContext context);

    MediaPacketSourceDescription describe();
}
