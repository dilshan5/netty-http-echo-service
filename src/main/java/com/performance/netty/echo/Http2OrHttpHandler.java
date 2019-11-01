package com.performance.netty.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;

/**
 * Negotiates with the client if HTTP2 or HTTP is going to be used. Once decided, the
 * pipeline is setup with the correct handlers for the selected protocol.
 */
public class Http2OrHttpHandler extends ApplicationProtocolNegotiationHandler {

    private static final int MAX_CONTENT_LENGTH = 1024 * 100;
    private final long sleepTime;
    private final boolean h2AggregateContent;

    Http2OrHttpHandler(long sleepTime, boolean h2AggregateContent) {
        super(ApplicationProtocolNames.HTTP_1_1);
        this.sleepTime = sleepTime;
        this.h2AggregateContent = h2AggregateContent;
    }

    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {

            if (h2AggregateContent) {
                DefaultHttp2Connection connection = new DefaultHttp2Connection(true);
                InboundHttp2ToHttpAdapter listener = new InboundHttp2ToHttpAdapterBuilder(connection)
                        .propagateSettings(true)
                        .validateHttpHeaders(false)
                        .maxContentLength(MAX_CONTENT_LENGTH).build();
                ctx.pipeline().addLast(new HttpToHttp2ConnectionHandlerBuilder()
                        .frameListener(listener)
                        .connection(connection).build());
                ctx.pipeline().addLast(new EchoHttpServerHandler(sleepTime, true));
            } else {
                ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build(), new EchoHttp2ServerHandler());
            }
            return;
        }

        if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
            ctx.pipeline().addLast(new HttpServerCodec(),
                    new HttpObjectAggregator(MAX_CONTENT_LENGTH),
                    new EchoHttpServerHandler(sleepTime, false));
            return;
        }

        throw new IllegalStateException("Unknown protocol: " + protocol);
    }
}
