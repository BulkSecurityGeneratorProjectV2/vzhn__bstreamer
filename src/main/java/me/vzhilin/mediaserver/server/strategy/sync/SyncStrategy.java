package me.vzhilin.mediaserver.server.strategy.sync;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelMatchers;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import me.vzhilin.mediaserver.InterleavedFrame;
import me.vzhilin.mediaserver.conf.Config;
import me.vzhilin.mediaserver.conf.SyncStrategyLimits;
import me.vzhilin.mediaserver.media.file.*;
import me.vzhilin.mediaserver.server.RtpEncoder;
import me.vzhilin.mediaserver.server.stat.ServerStatistics;
import me.vzhilin.mediaserver.server.strategy.StreamingStrategy;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class SyncStrategy implements StreamingStrategy {
    private final static Logger LOG = Logger.getLogger(SyncStrategy.class);

    /** executor */
    private final ScheduledExecutorService scheduledExecutor;
    private final ChannelGroup group;
    private final MediaPaketSourceConfig sourceConfig;
    private final MediaPacketSourceFactory sourceFactory;
    private final ServerStatistics stat;

    private final int SIZE_LIMIT;
    private final int PACKET_LIMIT;
    private final int TIME_LIMIT_MS;

    private ScheduledFuture<?> streamingFuture;
    private MediaPacketSource source;
    private Runnable command;

    public SyncStrategy(MediaPacketSourceFactory sourceFactory,
                        MediaPaketSourceConfig sourceConfig,
                        ScheduledExecutorService scheduledExecutor,
                        ServerStatistics stat,
                        Config config) {

        SyncStrategyLimits limits = config.getSyncStrategyLimits();
        SIZE_LIMIT = limits.getSize();
        PACKET_LIMIT = limits.getPackets();
        TIME_LIMIT_MS = limits.getTime();
        this.sourceFactory = sourceFactory;
        this.sourceConfig = sourceConfig;
        this.scheduledExecutor = scheduledExecutor;
        this.group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        this.stat = stat;
    }

    @Override
    public void attachContext(ChannelHandlerContext ctx) {
        boolean wasFirst = group.isEmpty();
        Channel ch = ctx.channel();
        group.add(ch);
        ch.closeFuture().addListener((ChannelFutureListener) future -> detachContext(ctx));

        if (wasFirst) {
            startPlaying();
        }
    }

    @Override
    public void detachContext(ChannelHandlerContext context) {
        group.remove(context.channel());

        boolean wasLast = group.isEmpty();
        if (wasLast) {
            stopPlaying();
        }
    }

    @Override
    public MediaPacketSourceDescription describe() {
        MediaPacketSource src = sourceFactory.newSource(sourceConfig);
        MediaPacketSourceDescription desc = src.getDesc();
        try {
            src.close();
        } catch (IOException e) {
            LOG.error(e, e);
        }
        return desc;
    }

    private void startPlaying() {
        source = sourceFactory.newSource(sourceConfig);
        command = new SyncWorker();
        streamingFuture = scheduledExecutor.schedule(command, 0, TimeUnit.NANOSECONDS);
    }

    private void stopPlaying() {
        streamingFuture.cancel(false);
        group.close();
        try {
            source.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final class SyncWorker implements Runnable {
        private boolean firstFrame = false;
        private final RtpEncoder encoder;
        private long firstDts;
        private long timeStarted;
        private long rtpSeqNo;
        private int notWritable;
        private long prevMillis;
        private long adjust;
        private boolean loop = true;
        private final List<MediaPacket> packets = new ArrayList<>();

        private SyncWorker() {
            encoder = new RtpEncoder();
            rtpSeqNo = 0;
            prevMillis = System.currentTimeMillis();
        }

        @Override
        public void run() {
            long nowMillis = System.currentTimeMillis();
            long deltaMillis = nowMillis - prevMillis;
            prevMillis = nowMillis;

            long sleepMillis;
            if (!firstFrame) {
                firstFrame = true;
                MediaPacket pkt;
                do {
                    pkt = source.next();
                    packets.add(pkt);
                } while (pkt.getDts() < 0);
                firstDts = pkt.getDts();
                timeStarted = nowMillis;
                send(packets);
                sleepMillis = 0;
                adjust = 0;
            } else {
                if (!source.hasNext()) {
                    if (loop) {
                        try {
                            source.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        source = sourceFactory.newSource(sourceConfig);
                        firstFrame = false;
                        sleepMillis = 40;
                    } else {
                        stopPlaying();
                        return;
                    }
                } else {
                    if (isChannelsWritable()) {
                        long sz = 0;
                        long np = 0;
                        long deltaPositionMillis = 0;

                        while (source.hasNext() && (sz < SIZE_LIMIT &&
                                                    np < PACKET_LIMIT &&
                                                    deltaPositionMillis < TIME_LIMIT_MS)) {
                            MediaPacket pkt = source.next();
                            packets.add(pkt);
                            sz += pkt.getPayload().readableBytes();
                            np += 1;
                            deltaPositionMillis = (pkt.getDts() - firstDts) - (nowMillis - timeStarted) + adjust;
                        }
                        send(packets);
                        sleepMillis = deltaPositionMillis;
                    } else {
                        System.err.println(new Date() + "overflow!");
                        adjust += deltaMillis;
                        sleepMillis = deltaMillis;
                        stat.getLagMillis().inc(deltaMillis);
                    }
                }
            }
            streamingFuture = scheduledExecutor.schedule(command, sleepMillis, TimeUnit.MILLISECONDS);
        }

        private void send(List<MediaPacket> packets) {
            int connectedClients = group.size();
            if (connectedClients >= 1) {
                int sz = estimateSize(packets);
                ByteBuf buffer = PooledByteBufAllocator.DEFAULT.buffer(sz, sz);
                for (int i = 0; i < packets.size(); i++) {
                    MediaPacket pkt = packets.get(i);
                    encoder.encode(buffer, pkt, rtpSeqNo++, pkt.getDts() * 90);
                }

                buffer.retain(connectedClients);
                group.writeAndFlush(new InterleavedFrame(buffer), ChannelMatchers.all(), true);
                buffer.release();
                stat.getThroughputMeter().mark((long) 8 * sz * connectedClients);
            }
            packets.forEach(mediaPacket -> mediaPacket.getPayload().release());
            packets.clear();
        }

        private int estimateSize(List<MediaPacket> packets) {
            int sz = 0;
            for (int i = 0; i < packets.size(); i++) {
                sz += encoder.estimateSize(packets.get(i));
            }
            return sz;
        }
        private boolean isChannelsWritable() {
            notWritable = 0;
            group.forEach(channel -> {
                if (!channel.isWritable()) {
                    ++notWritable;
                }
            });

            return notWritable == 0;
        }
    }
}
