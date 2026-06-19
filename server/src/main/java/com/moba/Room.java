package com.moba;

import com.google.gson.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Room {
    private final String roomId;
    private final String creatorId;
    private final Map<String, ChannelHandlerContext> players = new ConcurrentHashMap<>();
    private final Map<String, float[]> playerPositions = new ConcurrentHashMap<>();
    private final Map<String, String> playerHeroTypes = new ConcurrentHashMap<>();
    private final Map<String, String> playerStates = new ConcurrentHashMap<>();

    public Room(String roomId, String creatorId, ChannelHandlerContext ctx) {
        this.roomId = roomId;
        this.creatorId = creatorId;
        this.players.put(creatorId, ctx);
        this.playerPositions.put(creatorId, new float[]{0f, 0f});
        this.playerStates.put(creatorId, "IDLE");
    }

    public String getRoomId() { return roomId; }
    public String getCreatorId() { return creatorId; }
    public boolean isEmpty() { return players.isEmpty(); }

    public void addPlayer(String userId, ChannelHandlerContext ctx) {
        players.put(userId, ctx);
        playerPositions.put(userId, new float[]{0f, 0f});
        playerStates.put(userId, "IDLE");
    }

    public String removePlayer(ChannelHandlerContext ctx) {
        String userId = getUserId(ctx);
        if (userId != null) {
            players.remove(userId);
            playerPositions.remove(userId);
            playerHeroTypes.remove(userId);
            playerStates.remove(userId);
        }
        return userId;
    }

    public void updatePosition(String userId, float x, float z) {
        float[] pos = playerPositions.get(userId);
        if (pos != null) { pos[0] = x; pos[1] = z; }
    }

    public float[] getPosition(String userId) {
        return playerPositions.get(userId);
    }

    public void setHeroType(String userId, String heroType) {
        playerHeroTypes.put(userId, heroType);
    }

    public String getHeroType(String userId) {
        return playerHeroTypes.get(userId);
    }

    public void setState(String userId, String state) {
        playerStates.put(userId, state);
    }

    public String getState(String userId) {
        return playerStates.get(userId);
    }

    public JsonArray getPlayerList() {
        JsonArray arr = new JsonArray();
        for (String userId : players.keySet()) { arr.add(userId); }
        return arr;
    }

    public JsonArray getPlayerPositions() {
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, float[]> e : playerPositions.entrySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("userId", e.getKey());
            obj.addProperty("x", e.getValue()[0]);
            obj.addProperty("z", e.getValue()[1]);
            String hero = playerHeroTypes.get(e.getKey());
            if (hero != null) obj.addProperty("heroType", hero);
            String state = playerStates.get(e.getKey());
            if (state != null) obj.addProperty("state", state);
            arr.add(obj);
        }
        return arr;
    }

    public void broadcast(JsonObject msg, ChannelHandlerContext exclude) {
        String json = msg.toString();
        for (Map.Entry<String, ChannelHandlerContext> e : players.entrySet()) {
            if (e.getValue() != exclude) {
                e.getValue().writeAndFlush(new TextWebSocketFrame(json));
            }
        }
    }

    public void broadcastAll(JsonObject msg) {
        String json = msg.toString();
        for (ChannelHandlerContext ctx : players.values()) {
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    public String getUserId(ChannelHandlerContext ctx) {
        for (Map.Entry<String, ChannelHandlerContext> e : players.entrySet()) {
            if (e.getValue() == ctx) return e.getKey();
        }
        return null;
    }
}
