package com.yt.download.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class VideoController {

    private final String YT_DLP_PATH = System.getenv().getOrDefault("YT_DLP_PATH", "yt-dlp");
private final String TMP_DIR = System.getenv().getOrDefault("TMP_DIR", System.getProperty("java.io.tmpdir"));



@PostMapping("/info")
public ResponseEntity<String> getVideoInfo(@RequestBody Map<String, String> payload) {
    String videoUrl = payload.get("url");
    if (videoUrl == null || videoUrl.isBlank()) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Falta url\"}");
    }

    try {
        ProcessBuilder pb = new ProcessBuilder(
                YT_DLP_PATH,
                "--no-warnings",
                "--dump-json",
                videoUrl
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println("[yt-dlp] " + line);
                out.append(line);
            }
        }

        int exit = process.waitFor();
        System.out.println("yt-dlp exit code: " + exit);

        if (exit != 0 || out.isEmpty()) {
            return ResponseEntity.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"yt-dlp fallo en /info\",\"exitCode\":" + exit + "}");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(out.toString());

    } catch (Exception e) {
        e.printStackTrace();
        return ResponseEntity.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"No se pudo obtener la info\",\"detail\":\"" + e.getMessage() + "\"}");
    }
}


@GetMapping("/ping")
public ResponseEntity<String> ping() {
  return ResponseEntity.ok("ok");
}

@PostMapping("/download")
public ResponseEntity<StreamingResponseBody> downloadVideo(@RequestBody Map<String, String> payload) {
    System.out.println(">>> /api/download HIT payload=" + payload);

    String videoUrl = payload.get("url");
    String formatId = payload.getOrDefault("formatId", "best");

    if (videoUrl == null || videoUrl.isBlank()) {
        // Ojo: aquí el método retorna StreamingResponseBody, entonces devolvemos 400 con body vacío o cambia la firma a ResponseEntity<?>
        // Para mantenerlo simple, te recomiendo cambiar la firma a ResponseEntity<?> si quieres devolver JSON.
        return ResponseEntity.badRequest()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(outputStream -> outputStream.write("{\"error\":\"Falta url\"}".getBytes()));
    }

    String baseName = "video_" + UUID.randomUUID();
    String outTemplate = Paths.get(TMP_DIR, baseName + ".%(ext)s").toString();
    
    Path mp4Path = Paths.get(TMP_DIR, baseName + ".mp4");
    Path webmPath = Paths.get(TMP_DIR, baseName + ".webm");
    Path mkvPath = Paths.get(TMP_DIR, baseName + ".mkv");
    

    StreamingResponseBody stream = outputStream -> {
        Path finalPath = null;

        try {
            // Selector estable: prioriza MP4 + M4A
            String formatSelector =
                    formatId + "+bestaudio[ext=m4a]/" +
                    "bestvideo[ext=mp4]+bestaudio[ext=m4a]/" +
                    "best[ext=mp4]";

                    ProcessBuilder pb = new ProcessBuilder(
                        YT_DLP_PATH,
                        "-f", formatSelector,
                        "--merge-output-format", "mp4",
                        "-o", outTemplate,
                        videoUrl
                      );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Leer logs de yt-dlp (IMPORTANTÍSIMO para debug)
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[yt-dlp] " + line);
                }
            }

            int exitCode = process.waitFor();
            System.out.println(">>> yt-dlp exitCode=" + exitCode);

            // Detectar archivo final generado
            if (Files.exists(mp4Path)) finalPath = mp4Path;
            else if (Files.exists(webmPath)) finalPath = webmPath;
            else if (Files.exists(mkvPath)) finalPath = mkvPath;

            if (exitCode != 0 || finalPath == null) {
                throw new RuntimeException("yt-dlp no generó el archivo final. Revisa los logs [yt-dlp].");
            }

            // Stream al cliente
            try (InputStream is = Files.newInputStream(finalPath)) {
                is.transferTo(outputStream);
            }

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        } finally {
            // Limpieza
            try { Files.deleteIfExists(mp4Path); } catch (Exception ignored) {}
            try { Files.deleteIfExists(webmPath); } catch (Exception ignored) {}
            try { Files.deleteIfExists(mkvPath); } catch (Exception ignored) {}
        }
    };

    return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"video.mp4\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(stream);
}
}
