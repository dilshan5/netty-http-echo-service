package com.performance.netty.echo;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.HttpConversionUtil;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Handler implementation for the echo server and http/2 echo server with content aggregation.
 * For http/2 echo server with content aggregation, this receives a {@link FullHttpRequest},
 * which has been converted by a {@link io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter} before it arrives here.
 * For further details, check {@link Http2OrHttpHandler} where the pipeline is setup.
 */
@Sharable
public class EchoHttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private long sleepTime;
    private boolean h2AggregateContent;

    EchoHttpServerHandler(long sleepTime, boolean h2AggregateContent) {
        this.sleepTime = sleepTime;
        this.h2AggregateContent = h2AggregateContent;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        request.headers().set("Backend-IN-time", OffsetDateTime.now(ZoneOffset.UTC));
        if (h2AggregateContent) {
            String streamId = request.headers().get(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
            FullHttpResponse response = buildFullHttpResponse(request);
            response.headers().set(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), streamId);
            ctx.writeAndFlush(response);
        } else {
            // Decide whether to close the connection or not
            boolean keepAlive = HttpUtil.isKeepAlive(request);
            // Build the response object
            FullHttpResponse response = buildFullHttpResponse(request);
            if (keepAlive) {
                // Add keep alive header
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
            if (sleepTime > 0) {
                ctx.executor().schedule(() -> {
                    ChannelFuture f = ctx.writeAndFlush(response);
                    if (!keepAlive) {
                        f.addListener(ChannelFutureListener.CLOSE);
                    }
                }, sleepTime, TimeUnit.MILLISECONDS);
            } else {
                ChannelFuture f = ctx.writeAndFlush(response);
                if (!keepAlive) {
                    f.addListener(ChannelFutureListener.CLOSE);
                }
            }
        }
    }

    private static FullHttpResponse buildFullHttpResponse(FullHttpRequest request) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, request.content().copy());
        response.headers().set("Backend-OUT-time", OffsetDateTime.now(ZoneOffset.UTC));
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType != null) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        if (request.headers().get("Jmeter-OUT-time") != null)
            response.headers().set("Jmeter-OUT-time", request.headers().get("Jmeter-OUT-time"));
        response.headers().set("Backend-IN-time", request.headers().get("Backend-IN-time"));
        if (request.headers().get("APIC-request-id") != null)
            response.headers().set("APIC-request-id", request.headers().get("APIC-request-id"));
        return response;
    }
}
