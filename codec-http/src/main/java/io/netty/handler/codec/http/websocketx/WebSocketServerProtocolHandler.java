/*
 * Copyright 2019 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http.websocketx;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AttributeKey;

import java.util.List;

import static io.netty.handler.codec.http.HttpVersion.*;
import static io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig.DEFAULT;
import static io.netty.util.internal.ObjectUtil.*;

/**
 * This handler does all the heavy lifting for you to run a websocket server.
 *
 * It takes care of websocket handshaking as well as processing of control frames (Close, Ping, Pong). Text and Binary
 * data frames are passed to the next handler in the pipeline (implemented by you) for processing.
 *
 * See <tt>io.netty.example.http.websocketx.html5.WebSocketServer</tt> for usage.
 *
 * The implementation of this handler assumes that you just want to run  a websocket server and not process other types
 * HTTP requests (like GET and POST). If you wish to support both HTTP requests and websockets in the one server, refer
 * to the <tt>io.netty.example.http.websocketx.server.WebSocketServer</tt> example.
 *
 * To know once a handshake was done you can intercept the
 * {@link ChannelInboundHandler#userEventTriggered(ChannelHandlerContext, Object)} and check if the event was instance
 * of {@link HandshakeComplete}, the event will contain extra information about the handshake such as the request and
 * selected subprotocol.
 */
public class WebSocketServerProtocolHandler extends WebSocketProtocolHandler {

    /**
     * Events that are fired to notify about handshake status
     */
    public enum ServerHandshakeStateEvent {
        /**
         * The Handshake was completed successfully and the channel was upgraded to websockets.
         *
         * @deprecated in favor of {@link HandshakeComplete} class,
         * it provides extra information about the handshake
         */
        @Deprecated
        HANDSHAKE_COMPLETE,

        /**
         * The Handshake was timed out
         */
        HANDSHAKE_TIMEOUT
    }

    /**
     * The Handshake was completed successfully and the channel was upgraded to websockets.
     */
    public static final class HandshakeComplete {
        private final String requestUri;
        private final HttpHeaders requestHeaders;
        private final String selectedSubprotocol;

        HandshakeComplete(String requestUri, HttpHeaders requestHeaders, String selectedSubprotocol) {
            this.requestUri = requestUri;
            this.requestHeaders = requestHeaders;
            this.selectedSubprotocol = selectedSubprotocol;
        }

        public String requestUri() {
            return requestUri;
        }

        public HttpHeaders requestHeaders() {
            return requestHeaders;
        }

        public String selectedSubprotocol() {
            return selectedSubprotocol;
        }
    }

    private static final AttributeKey<WebSocketServerHandshaker> HANDSHAKER_ATTR_KEY =
            AttributeKey.valueOf(WebSocketServerHandshaker.class, "HANDSHAKER");

    private final WebSocketServerProtocolConfig serverConfig;

    /**
     * Base constructor
     *
     * @param serverConfig
     *            Server protocol configuration.
     */
    public WebSocketServerProtocolHandler(WebSocketServerProtocolConfig serverConfig) {
        super(checkNotNull(serverConfig, "serverConfig").dropPongFrames());
        this.serverConfig = serverConfig;
    }

    public WebSocketServerProtocolHandler(String websocketPath) {
        this(websocketPath, DEFAULT.handshakeTimeoutMillis());
    }

    public WebSocketServerProtocolHandler(String websocketPath, long handshakeTimeoutMillis) {
        this(websocketPath, false, handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, boolean checkStartsWith) {
        this(websocketPath, checkStartsWith, DEFAULT.handshakeTimeoutMillis());
    }

    public WebSocketServerProtocolHandler(String websocketPath, boolean checkStartsWith, long handshakeTimeoutMillis) {
        this(websocketPath, null, false, 65536, false, checkStartsWith, handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols) {
        this(websocketPath, subprotocols, DEFAULT.handshakeTimeoutMillis());
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, false, handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions) {
        this(websocketPath, subprotocols, allowExtensions, DEFAULT.handshakeTimeoutMillis());
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions,
                                          long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, 65536, handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
                                          boolean allowExtensions, int maxFrameSize) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, DEFAULT.handshakeTimeoutMillis());
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
                                          boolean allowExtensions, int maxFrameSize, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, false, handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
            boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch,
            DEFAULT.handshakeTimeoutMillis());
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions,
                                          int maxFrameSize, boolean allowMaskMismatch, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, false,
             handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
            boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch, boolean checkStartsWith) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, checkStartsWith,
            DEFAULT.handshakeTimeoutMillis());
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
                                          boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch,
                                          boolean checkStartsWith, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, checkStartsWith, true,
             handshakeTimeoutMillis);
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols,
                                          boolean allowExtensions, int maxFrameSize, boolean allowMaskMismatch,
                                          boolean checkStartsWith, boolean dropPongFrames) {
        this(websocketPath, subprotocols, allowExtensions, maxFrameSize, allowMaskMismatch, checkStartsWith,
             dropPongFrames, DEFAULT.handshakeTimeoutMillis());
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean allowExtensions,
                                          int maxFrameSize, boolean allowMaskMismatch, boolean checkStartsWith,
                                          boolean dropPongFrames, long handshakeTimeoutMillis) {
        this(websocketPath, subprotocols, checkStartsWith, dropPongFrames, handshakeTimeoutMillis,
            WebSocketDecoderConfig.newBuilder()
                .maxFramePayloadLength(maxFrameSize)
                .allowMaskMismatch(allowMaskMismatch)
                .allowExtensions(allowExtensions)
                .build());
    }

    public WebSocketServerProtocolHandler(String websocketPath, String subprotocols, boolean checkStartsWith,
                                          boolean dropPongFrames, long handshakeTimeoutMillis,
                                          WebSocketDecoderConfig decoderConfig) {
        this(WebSocketServerProtocolConfig.newBuilder()
            .websocketPath(websocketPath)
            .subprotocols(subprotocols)
            .checkStartsWith(checkStartsWith)
            .handshakeTimeoutMillis(handshakeTimeoutMillis)
            .dropPongFrames(dropPongFrames)
            .decoderConfig(decoderConfig)
            .build());
    }

    //初始化添加的时候，会调用handlerAdded进行处理器的添加，分别添加握手处理器WebSocketServerProtocolHandshakeHandler，
	// UTF8文本帧验证器Utf8FrameValidator，关闭帧处理器WebSocketCloseFrameHandler
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ChannelPipeline cp = ctx.pipeline();
        if (cp.get(WebSocketServerProtocolHandshakeHandler.class) == null) {
            // Add the WebSocketHandshakeHandler before this one.
			//在前面添加一个握手处理器
            cp.addBefore(ctx.name(), WebSocketServerProtocolHandshakeHandler.class.getName(),
                    new WebSocketServerProtocolHandshakeHandler(serverConfig));
        }
        if (serverConfig.decoderConfig().withUTF8Validator() && cp.get(Utf8FrameValidator.class) == null) {
            // Add the UFT8 checking before this one.
			//在前面添加帧验证器
            cp.addBefore(ctx.name(), Utf8FrameValidator.class.getName(),
                    new Utf8FrameValidator());
        }
        if (serverConfig.sendCloseFrame() != null) {//添加关闭帧处理器
            cp.addBefore(ctx.name(), WebSocketCloseFrameHandler.class.getName(),
                new WebSocketCloseFrameHandler(serverConfig.sendCloseFrame(), serverConfig.forceCloseTimeoutMillis()));
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) throws Exception {
        if (serverConfig.handleCloseFrames() && frame instanceof CloseWebSocketFrame) {
            WebSocketServerHandshaker handshaker = getHandshaker(ctx.channel());
            if (handshaker != null) {
                frame.retain();
                handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame);
            } else {
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
            return;
        }
        super.decode(ctx, frame, out);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof WebSocketHandshakeException) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, HttpResponseStatus.BAD_REQUEST, Unpooled.wrappedBuffer(cause.getMessage().getBytes()));
            ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.fireExceptionCaught(cause);
            ctx.close();
        }
    }

    static WebSocketServerHandshaker getHandshaker(Channel channel) {
        return channel.attr(HANDSHAKER_ATTR_KEY).get();
    }

    static void setHandshaker(Channel channel, WebSocketServerHandshaker handshaker) {
        channel.attr(HANDSHAKER_ATTR_KEY).set(handshaker);
    }

    //用来创建禁止HTTP请求的响应器，只要握手对象创建好了，就不需要响应HTTP了，直接就把当前处理器WebSocketServerProtocolHandler给替换
    static ChannelHandler forbiddenHttpRequestResponder() {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof FullHttpRequest) {
                    ((FullHttpRequest) msg).release();
                    FullHttpResponse response =
                            new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.FORBIDDEN, ctx.alloc().buffer(0));
                    ctx.channel().writeAndFlush(response);//从通道尾部开始
                } else {
                    ctx.fireChannelRead(msg);
                }
            }
        };
    }
}
