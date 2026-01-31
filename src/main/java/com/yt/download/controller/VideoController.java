package com.yt.download.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.Base64;

@RestController
@RequestMapping("/api")
public class VideoController {

    private final String YT_DLP_PATH =
            System.getenv().getOrDefault("YT_DLP_PATH", "yt-dlp");

    private final String TMP_DIR =
            System.getenv().getOrDefault("TMP_DIR", System.getProperty("java.io.tmpdir"));

    /* ======================================================
       COOKIES (BASE64 – recomendado para deploy)
       ====================================================== */
    private String prepareCookies() {
        String cookiesB64 = System.getenv("YT_COOKIES_B64");

        if (cookiesB64 == null || cookiesB64.isBlank()) {
            System.out.println("[AUTH] No cookies configuradas");
            return null;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(cookiesB64);
            Path path = Paths.get(TMP_DIR, "cookies.txt");
            Files.write(path, decoded);

            System.out.println("[AUTH] cookies.txt creado (" + Files.size(path) + " bytes)");
            return path.toString();
        } catch (Exception e) {
            System.err.println("[AUTH] Error escribiendo cookies: " + e.getMessage());
            return null;
        }
    }

    /* ======================================================
       /api/ping
       ====================================================== */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("ok");
    }

    /* ======================================================
       /api/info
       ====================================================== */
    @PostMapping("/info")
    public ResponseEntity<String> getVideoInfo(@RequestBody Map<String, String> payload) {

        String videoUrl = payload.get("url");
        if (videoUrl == null || videoUrl.isBlank()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Falta url\"}");
        }

        try {
            String cookiesPath = prepareCookies();

            List<String> command = new ArrayList<>(List.of(
                    YT_DLP_PATH,
                    "--no-warnings",
                    "--dump-json"
            ));

            if (cookiesPath != null) {
                command.add("--cookies");
                command.add(cookiesPath);
            }

            command.add(videoUrl);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[yt-dlp] " + line);
                    out.append(line);
                }
            }

            int exit;
try {
    exit = process.waitFor();
} catch (InterruptedException ie) {
    Thread.currentThread().interrupt(); // importante
    return ResponseEntity.status(500)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"Proceso interrumpido\"}");
}
            System.out.println("[yt-dlp] exitCode=" + exit);

            if (exit != 0 || out.isEmpty()) {
                return ResponseEntity.status(500)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"yt-dlp fallo\",\"exitCode\":" + exit + "}");
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(out.toString());

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"No se pudo obtener info\"}");
        }
    }

    /* ======================================================
       /api/download
       ====================================================== */
    @PostMapping("/download")
    public ResponseEntity<StreamingResponseBody> downloadVideo(
            @RequestBody Map<String, String> payload) {

        String videoUrl = payload.get("url");
        String formatId = payload.getOrDefault("formatId", "best");

        if (videoUrl == null || videoUrl.isBlank()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(os -> os.write("{\"error\":\"Falta url\"}".getBytes()));
        }

        String baseName = "video_" + UUID.randomUUID();
        String outTemplate = Paths.get(TMP_DIR, baseName + ".%(ext)s").toString();

        Path mp4Path = Paths.get(TMP_DIR, baseName + ".mp4");
        Path webmPath = Paths.get(TMP_DIR, baseName + ".webm");
        Path mkvPath = Paths.get(TMP_DIR, baseName + ".mkv");

        StreamingResponseBody stream = outputStream -> {

            Path finalPath = null;

            try {
                String cookiesPath = prepareCookies();

                String formatSelector =
                        formatId + "+bestaudio[ext=m4a]/" +
                        "bestvideo[ext=mp4]+bestaudio[ext=m4a]/" +
                        "best[ext=mp4]";

                List<String> command = new ArrayList<>(List.of(
                        YT_DLP_PATH,
                        "-f", formatSelector,
                        "--merge-output-format", "mp4",
                        "-o", outTemplate
                ));

                if (cookiesPath != null) {
                    command.add("--cookies");
                    command.add(cookiesPath);
                }

                command.add(videoUrl);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);

                Process process = pb.start();

                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        System.out.println("[yt-dlp] " + line);
                    }
                }

                int exit;
try {
    exit = process.waitFor();
} catch (InterruptedException ie) {
    Thread.currentThread().interrupt(); // importante
    throw new RuntimeException("Descarga interrumpida", ie);
}
                System.out.println("[yt-dlp] exitCode=" + exit);

                if (Files.exists(mp4Path)) finalPath = mp4Path;
                else if (Files.exists(webmPath)) finalPath = webmPath;
                else if (Files.exists(mkvPath)) finalPath = mkvPath;

                if (exit != 0 || finalPath == null) {
                    throw new RuntimeException("yt-dlp no generó archivo");
                }

                try (InputStream is = Files.newInputStream(finalPath)) {
                    is.transferTo(outputStream);
                }

            } finally {
                Files.deleteIfExists(mp4Path);
                Files.deleteIfExists(webmPath);
                Files.deleteIfExists(mkvPath);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"video.mp4\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(stream);
    }
}
