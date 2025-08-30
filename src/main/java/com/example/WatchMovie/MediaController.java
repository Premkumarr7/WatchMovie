package com.example.WatchMovie;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/media")
@CrossOrigin
public class MediaController {

    // Upload folder path
    private final Path root = Paths.get("uploads");

    // Stream video with optional Range support
    @GetMapping("/{roomId}/{fileName}")
    public ResponseEntity<Resource> stream(@PathVariable String roomId,
                                           @PathVariable String fileName,
                                           @RequestHeader(value = "Range", required = false) String range) throws IOException {

        Path file = root.resolve(roomId).resolve(fileName);

        if (!Files.exists(file)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        long fileSize = Files.size(file);
        String contentType = Files.probeContentType(file);
        if (contentType == null) contentType = "video/mp4";

        // No Range header -> full content
        if (range == null) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            headers.setContentLength(fileSize);

            InputStreamResource resource = new InputStreamResource(Files.newInputStream(file));
            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        }

        // Parse Range header (bytes=start-end)
        String[] parts = range.replace("bytes=", "").split("-");
        long start = Long.parseLong(parts[0]);
        long end;
        if (parts.length > 1 && parts[1] != null && !parts[1].trim().isEmpty()) {
            end = Long.parseLong(parts[1]);
        } else {
            end = Math.min(start + 1024 * 1024 - 1, fileSize - 1); // 1MB max chunk
        }
        end = Math.min(end, fileSize - 1);
        long contentLength = end - start + 1;

        RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
        raf.seek(start);
        byte[] data = new byte[(int) contentLength];
        raf.readFully(data);
        raf.close();

        InputStreamResource resource = new InputStreamResource(new java.io.ByteArrayInputStream(data));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_RANGE, String.format("bytes %d-%d/%d", start, end, fileSize));
        headers.setContentLength(contentLength);

        return new ResponseEntity<>(resource, headers, HttpStatus.PARTIAL_CONTENT);
    }
}
