package com.example.WatchMovie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // All rooms in memory
    private static final Map<String, Room> ROOMS = new ConcurrentHashMap<>();

    public static Room getOrCreateRoom(String roomId) {
        return ROOMS.computeIfAbsent(roomId, Room::new);
    }

    private static String queryParam(URI uri, String key) {
        if (uri.getQuery() == null) return null;
        for (String pair : uri.getQuery().split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                try {
                    String k = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    if (k.equals(key)) return v;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null) { session.close(); return; }

        String roomId = queryParam(uri, "roomId");
        String name = Optional.ofNullable(queryParam(uri, "name")).orElse("Guest");

        if (roomId == null || roomId.trim().isEmpty()) {
            session.close();
            return;
        }

        Room room = getOrCreateRoom(roomId);
        String clientId = UUID.randomUUID().toString();

        room.getSessions().add(session);
        room.getClientIdToSession().put(clientId, session);
        room.getSessionIdToClientId().put(session.getId(), clientId);
        room.getClientIdToName().put(clientId, name);
        session.getAttributes().put("roomId", roomId);
        session.getAttributes().put("clientId", clientId);

        // Send client ID and current room state
        ObjectNode welcome = mapper.createObjectNode();
        welcome.put("type", "client-id");
        welcome.put("clientId", clientId);
        welcome.put("roomId", roomId);
        welcome.put("name", name);

        if (room.getCurrentVideoUrl() != null) {
            ObjectNode src = mapper.createObjectNode();
            src.put("type", "setSource");
            src.put("url", room.getCurrentVideoUrl());
            src.put("fileName", room.getCurrentFileName() == null ? "" : room.getCurrentFileName());
            session.sendMessage(new TextMessage(src.toString()));
        }

        session.sendMessage(new TextMessage(welcome.toString()));

        // Notify others
        broadcastToRoomExcept(room, session, jsonMsg("member-joined", createMap("clientId", clientId, "name", name)));

        // Send current members to the new client
        List<ObjectNode> members = new ArrayList<>();
        for (Map.Entry<String, String> e : room.getClientIdToName().entrySet()) {
            ObjectNode m = mapper.createObjectNode();
            m.put("clientId", e.getKey());
            m.put("name", e.getValue());
            members.add(m);
        }
        ObjectNode list = mapper.createObjectNode();
        list.put("type", "member-list");
        list.set("members", mapper.valueToTree(members));
        session.sendMessage(new TextMessage(list.toString()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        JsonNode root = mapper.readTree(payload);
        String type = root.path("type").asText("");
        String roomId = (String) session.getAttributes().get("roomId");
        Room room = getOrCreateRoom(roomId);

        switch (type) {
            case "control": // play/pause/seek
            case "chat":
            case "setSource":
            case "introduce-broadcaster":
            case "end-broadcast":
                broadcastToRoomExcept(room, session, payload);
                if (type.equals("setSource")) {
                    room.setCurrentVideoUrl(root.path("url").asText(null));
                    room.setCurrentFileName(root.path("fileName").asText(null));
                } else if (type.equals("introduce-broadcaster")) {
                    String from = root.path("from").asText(null);
                    room.setBroadcasterClientId(from);
                } else if (type.equals("end-broadcast")) {
                    room.setBroadcasterClientId(null);
                }
                break;
            case "webrtc-offer":
            case "webrtc-answer":
            case "webrtc-ice":
                String to = root.path("to").asText("");
                WebSocketSession target = room.getClientIdToSession().get(to);
                if (target != null && target.isOpen()) {
                    target.sendMessage(new TextMessage(payload));
                }
                break;
            default:
                // ignore unknown types
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String roomId = (String) session.getAttributes().get("roomId");
        String clientId = (String) session.getAttributes().get("clientId");
        if (roomId == null || clientId == null) return;

        Room room = getOrCreateRoom(roomId);

        room.getSessions().remove(session);
        room.getClientIdToSession().remove(clientId);
        room.getSessionIdToClientId().remove(session.getId());
        String name = room.getClientIdToName().remove(clientId);

        broadcastToRoom(room, jsonMsg("member-left", createMap("clientId", clientId, "name", name == null ? "" : name)));
    }

    private void broadcastToRoom(Room room, String json) throws IOException {
        for (WebSocketSession s : room.getSessions()) {
            if (s.isOpen()) s.sendMessage(new TextMessage(json));
        }
    }

    private void broadcastToRoomExcept(Room room, WebSocketSession except, String json) throws IOException {
        for (WebSocketSession s : room.getSessions()) {
            if (s.isOpen() && s != except) s.sendMessage(new TextMessage(json));
        }
    }

    private String jsonMsg(String type, Map<String, Object> fields) throws IOException {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", type);
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            n.set(e.getKey(), mapper.valueToTree(e.getValue()));
        }
        return n.toString();
    }

    // Utility for creating simple maps in Java 8
    private static Map<String, Object> createMap(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
