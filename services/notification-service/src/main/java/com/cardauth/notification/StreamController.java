package com.cardauth.notification;

import com.cardauth.domain.Topics;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Consumes authorisation and settlement events and fans them out to dashboards over SSE. */
@RestController
@Component
@EnableScheduling
public class StreamController {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final String benchmarksFile;

    public StreamController(@Value("${app.benchmarks-file:}") String benchmarksFile) { this.benchmarksFile = benchmarksFile; }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    @KafkaListener(topics = Topics.AUTHORIZATIONS, groupId = "notification")
    public void onAuthorization(String payload) { push("auth", payload); }

    @KafkaListener(topics = Topics.SETTLEMENTS, groupId = "notification")
    public void onSettlement(String payload) { push("settlement", payload); }

    @Scheduled(fixedDelay = 15000)
    void heartbeat() { push("ping", "{}"); }

    private void push(String type, String json) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name(type).data(json));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(e);
            }
        }
    }

    /** Latest benchmark table: file from the benchmark run if mounted, else the copy packaged in the jar. */
    @GetMapping(path = "/benchmarks", produces = MediaType.APPLICATION_JSON_VALUE)
    public String benchmarks() throws IOException {
        if (!benchmarksFile.isBlank() && Files.exists(Path.of(benchmarksFile))) return Files.readString(Path.of(benchmarksFile));
        try (InputStream in = getClass().getResourceAsStream("/bench/benchmarks.json")) {
            return in == null ? "[]" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
