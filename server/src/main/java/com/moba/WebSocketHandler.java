package com.moba;

import com.google.gson.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.*;

@ChannelHandler.Sharable
public class WebSocketHandler extends SimpleChannelInboundHandler<Object> {
    private static final String WS_PATH = "/ws";
    private WebSocketServerHandshaker handshaker;
    private static final RoomManager roomManager = RoomManager.getInstance();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, (FullHttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!req.decoderResult().isSuccess()) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
            return;
        }
        if (req.method() == HttpMethod.OPTIONS) {
            FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, OK);
            setCorsHeaders(res);
            ctx.writeAndFlush(res);
            return;
        }
        if (WS_PATH.equals(req.uri()) && "websocket".equalsIgnoreCase(req.headers().get("Upgrade"))) {
            WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                getWebSocketLocation(req), null, true);
            handshaker = wsFactory.newHandshaker(req);
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            } else {
                handshaker.handshake(ctx.channel(), req);
                System.out.println("[WS] Connected: " + ctx.channel().remoteAddress());
            }
            return;
        }
        sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND));
    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame) {
            roomManager.handleDisconnect(ctx);
            handshaker.close(ctx.channel(), ((CloseWebSocketFrame) frame).retain());
            return;
        }
        if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (frame instanceof TextWebSocketFrame) {
            String text = ((TextWebSocketFrame) frame).text();
            try {
                JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
                String type = msg.get("type").getAsString();
                switch (type) {
                    case "CREATE_ROOM": roomManager.createRoom(ctx, msg); break;
                    case "JOIN_ROOM":   roomManager.joinRoom(ctx, msg);   break;
                    case "LEAVE_ROOM":  roomManager.leaveRoom(ctx, msg);  break;
                    case "MOVE":           roomManager.handleMove(ctx, msg);        break;
                    case "SELECT_HERO":   roomManager.handleSelectHero(ctx, msg);  break;
                    case "CHANGE_STATE":  roomManager.handleChangeState(ctx, msg);  break;
                    default:
                        System.out.println("[WS] Unknown type: " + type);
                        sendError(ctx, "Unknown message type: " + type);
                }
            } catch (Exception e) {
                System.err.println("[WS] Parse error: " + e.getMessage());
                sendError(ctx, "Invalid message format");
            }
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        roomManager.handleDisconnect(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[WS] Error: " + cause.getMessage());
        ctx.close();
    }

    private void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
        if (res.status().code() != 200) {
            ByteBuf buf = Unpooled.copiedBuffer(res.status().toString(), CharsetUtil.UTF_8);
            res.content().writeBytes(buf);
            buf.release();
            res.headers().setInt(CONTENT_LENGTH, res.content().readableBytes());
        }
        setCorsHeaders(res);
        ChannelFuture f = ctx.writeAndFlush(res);
        if (!HttpUtil.isKeepAlive(req) || res.status().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void setCorsHeaders(FullHttpResponse res) {
        res.headers().set("Access-Control-Allow-Origin", "*");
        res.headers().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.headers().set("Access-Control-Allow-Headers", "*");
    }

    private String getWebSocketLocation(FullHttpRequest req) {
        return "ws://" + req.headers().get(HOST) + WS_PATH;
    }

    private void sendError(ChannelHandlerContext ctx, String message) {
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "ERROR");
        resp.addProperty("message", message);
        ctx.writeAndFlush(new TextWebSocketFrame(resp.toString()));
    }
}