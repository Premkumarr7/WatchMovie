package com.example.WatchMovie;

import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
@CrossOrigin
public class RoomController {

    private final Path root = Paths.get("uploads"); // Java 8 compatible

    @PostMapping
    public Map<String, Object> createRoom() throws IOException {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Files.createDirectories(root.resolve(id));
        RoomWebSocketHandler.getOrCreateRoom(id);

        Map<String, Object> result = new HashMap<>();
        result.put("roomId", id);
        result.put("joinUrl", "/room.html?roomId=" + id);
        return result;
    }

    @GetMapping("/{roomId}")
    public Map<String, Object> getRoom(@PathVariable String roomId) {
        Room room = RoomWebSocketHandler.getOrCreateRoom(roomId);

        Map<String, Object> result = new HashMap<>();
        result.put("roomId", room.getId());
        result.put("currentVideoUrl", room.getCurrentVideoUrl());
        result.put("fileName", room.getCurrentFileName());
        return result;
    }

    @PostMapping("/{roomId}/upload")
    public ResponseEntity<?> upload(@PathVariable String roomId, @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Empty file");
            return ResponseEntity.badRequest().body(error);
        }

        String filename = StringUtils.cleanPath(file.getOriginalFilename());

        if (!filename.toLowerCase().matches(".*\\.(mp4|webm|ogg)$")) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Only mp4/webm/ogg allowed");
            return ResponseEntity.badRequest().body(error);
        }

        Files.createDirectories(root.resolve(roomId));
        Path dest = root.resolve(roomId).resolve(filename);
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);

        String url = "/media/" + roomId + "/" + filename;
        Room room = RoomWebSocketHandler.getOrCreateRoom(roomId);
        room.setCurrentVideoUrl(url);
        room.setCurrentFileName(filename);

        Map<String, Object> uploadResult = new HashMap<>();
        uploadResult.put("url", url);
        uploadResult.put("fileName", filename);
        return ResponseEntity.ok(uploadResult);
    }
}
