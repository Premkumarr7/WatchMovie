package com.example.WatchMovie;

import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Room {
    private final String id;
    private volatile String currentVideoUrl; // /media/{roomId}/{filename}
    private volatile String currentFileName;
    private volatile String broadcasterClientId; // who is sharing screen (if any)

    // Maps
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final Map<String, WebSocketSession> clientIdToSession = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdToClientId = new ConcurrentHashMap<>();
    private final Map<String, String> clientIdToName = new ConcurrentHashMap<>();

    public Room(String id) {
        this.id = id;
    }

    public String getId() { return id; }

    public Set<WebSocketSession> getSessions() { return sessions; }

    public Map<String, WebSocketSession> getClientIdToSession() { return clientIdToSession; }

    public Map<String, String> getSessionIdToClientId() { return sessionIdToClientId; }

    public Map<String, String> getClientIdToName() { return clientIdToName; }

    public String getCurrentVideoUrl() { return currentVideoUrl; }

    public void setCurrentVideoUrl(String currentVideoUrl) { this.currentVideoUrl = currentVideoUrl; }

    public String getCurrentFileName() { return currentFileName; }

    public void setCurrentFileName(String currentFileName) { this.currentFileName = currentFileName; }

    public String getBroadcasterClientId() { return broadcasterClientId; }

    public void setBroadcasterClientId(String broadcasterClientId) { this.broadcasterClientId = broadcasterClientId; }
}
