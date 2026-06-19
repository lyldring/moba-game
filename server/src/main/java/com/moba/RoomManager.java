package com.moba;

import com.google.gson.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RoomManager {
    private static final RoomManager INSTANCE = new RoomManager();
    public static RoomManager getInstance() { return INSTANCE; }

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<ChannelHandlerContext, String> playerToRoom = new ConcurrentHashMap<>();
    private final Random random = new Random();

    private RoomManager() {}

    // ===== CREATE_ROOM =====
    public void createRoom(ChannelHandlerContext ctx, JsonObject msg) {
        String userId = msg.get("userId").getAsString();
        String roomId = String.format("%04d", random.nextInt(10000));
        Room room = new Room(roomId, userId, ctx);
        rooms.put(roomId, room);
        playerToRoom.put(ctx, roomId);

        JsonObject resp = new JsonObject();
        resp.addProperty("type", "ROOM_CREATED");
        resp.addProperty("roomId", roomId);
        resp.add("players", room.getPlayerList());
        resp.add("positions", room.getPlayerPositions());
        send(ctx, resp);
        System.out.println("[Room] " + userId + " created room " + roomId);
    }

    // ===== JOIN_ROOM =====
    public void joinRoom(ChannelHandlerContext ctx, JsonObject msg) {
        String userId = msg.get("userId").getAsString();
        String roomId = msg.get("roomId").getAsString();
        Room room = rooms.get(roomId);
        if (room == null) { sendError(ctx, "Room " + roomId + " not found"); return; }

        room.addPlayer(userId, ctx);
        playerToRoom.put(ctx, roomId);

        JsonObject resp = new JsonObject();
        resp.addProperty("type", "ROOM_JOINED");
        resp.addProperty("roomId", roomId);
        resp.add("players", room.getPlayerList());
        resp.add("positions", room.getPlayerPositions());
        send(ctx, resp);

        broadcastRoomState(room, ctx);
        System.out.println("[Room] " + userId + " joined room " + roomId);
    }

    // ===== LEAVE_ROOM =====
    public void leaveRoom(ChannelHandlerContext ctx, JsonObject msg) {
        String roomId = playerToRoom.get(ctx);
        if (roomId == null) return;
        Room room = rooms.get(roomId);
        if (room == null) return;

        String userId = room.removePlayer(ctx);
        playerToRoom.remove(ctx);

        JsonObject resp = new JsonObject();
        resp.addProperty("type", "ROOM_LEFT");
        send(ctx, resp);
        System.out.println("[Room] " + userId + " left room " + roomId);

        if (room.isEmpty()) {
            rooms.remove(roomId);
            System.out.println("[Room] Room " + roomId + " destroyed (empty)");
        } else {
            broadcastRoomState(room, null);
        }
    }

    // ===== MOVE =====
    public void handleMove(ChannelHandlerContext ctx, JsonObject msg) {
        String roomId = playerToRoom.get(ctx);
        if (roomId == null) return;
        Room room = rooms.get(roomId);
        if (room == null) return;
        String userId = room.getUserId(ctx);
        if (userId == null) return;

        float x = msg.get("x").getAsFloat();
        float z = msg.get("z").getAsFloat();
        room.updatePosition(userId, x, z);

        JsonObject resp = new JsonObject();
        resp.addProperty("type", "PLAYER_MOVED");
        resp.addProperty("userId", userId);
        resp.addProperty("x", x);
        resp.addProperty("z", z);
        room.broadcast(resp, ctx);
    }

    // ===== SELECT_HERO =====
    public void handleSelectHero(ChannelHandlerContext ctx, JsonObject msg) {
        String roomId = playerToRoom.get(ctx);
        if (roomId == null) return;
        Room room = rooms.get(roomId);
        if (room == null) return;
        String userId = room.getUserId(ctx);
        if (userId == null) return;

        String heroType = msg.get("heroType").getAsString();
        room.setHeroType(userId, heroType);
        System.out.println("[Room] " + userId + " selected " + heroType + " in room " + roomId);

        // 广播给所有人（含自己）
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "HERO_SELECTED");
        resp.addProperty("userId", userId);
        resp.addProperty("heroType", heroType);
        room.broadcastAll(resp);
    }

    // ===== CHANGE_STATE =====
    public void handleChangeState(ChannelHandlerContext ctx, JsonObject msg) {
        String roomId = playerToRoom.get(ctx);
        if (roomId == null) return;
        Room room = rooms.get(roomId);
        if (room == null) return;
        String userId = room.getUserId(ctx);
        if (userId == null) return;

        String state = msg.get("state").getAsString();
        String oldState = room.getState(userId);
        if (state.equals(oldState)) return; // 状态没变，不广播
        room.setState(userId, state);

        JsonObject resp = new JsonObject();
        resp.addProperty("type", "STATE_CHANGED");
        resp.addProperty("userId", userId);
        resp.addProperty("state", state);
        room.broadcast(resp, ctx);
    }

    // ===== 断开连接 =====
    public void handleDisconnect(ChannelHandlerContext ctx) {
        String roomId = playerToRoom.remove(ctx);
        if (roomId == null) return;
        Room room = rooms.get(roomId);
        if (room == null) return;

        String userId = room.removePlayer(ctx);
        System.out.println("[Room] " + userId + " disconnected from room " + roomId);

        if (room.isEmpty()) {
            rooms.remove(roomId);
            System.out.println("[Room] Room " + roomId + " destroyed (empty)");
        } else {
            broadcastRoomState(room, null);
        }
    }

    private void broadcastRoomState(Room room, ChannelHandlerContext exclude) {
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "ROOM_STATE");
        resp.addProperty("roomId", room.getRoomId());
        resp.add("players", room.getPlayerList());
        resp.add("positions", room.getPlayerPositions());
        room.broadcast(resp, exclude);
    }

    private void send(ChannelHandlerContext ctx, JsonObject msg) {
        ctx.writeAndFlush(new TextWebSocketFrame(msg.toString()));
    }

    private void sendError(ChannelHandlerContext ctx, String message) {
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "ERROR");
        resp.addProperty("message", message);
        send(ctx, resp);
    }
}
