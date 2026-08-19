package com.oliver.erydon.client.model;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oliver.erydon.Erydon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class SynapheiaMetrics {
    private static final String PREFIX = "ERYDON_SYNAPHEIA_METRIC ";
    private static final boolean ENABLED = Boolean.getBoolean("erydon.synapheia.metrics");
    private static final String RUN_ID = System.getProperty("erydon.synapheia.run_id", "local");
    private static final String OUTPUT = System.getProperty("erydon.synapheia.metrics.output");
    private static final Gson GSON = new Gson();
    private static final Object OUTPUT_LOCK = new Object();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicBoolean OUTPUT_WARNING = new AtomicBoolean();

    private SynapheiaMetrics() {
    }

    static void event(String event,
                      SynapheiaMode mode,
                      long reloadGeneration,
                      Map<String, ?> fields) {
        if (!ENABLED) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("event", event);
        payload.addProperty("run_id", RUN_ID);
        payload.addProperty("sequence", SEQUENCE.incrementAndGet());
        payload.addProperty("engine", mode.configValue());
        payload.addProperty("mode", mode.configValue());
        payload.addProperty("thread", Thread.currentThread().getName());
        payload.addProperty("reload_generation", reloadGeneration);
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            JsonElement value = GSON.toJsonTree(entry.getValue());
            payload.add(entry.getKey(), value);
        }

        String line = PREFIX + GSON.toJson(payload);
        if (OUTPUT == null || OUTPUT.isBlank()) {
            Erydon.LOGGER.info(line);
            return;
        }

        synchronized (OUTPUT_LOCK) {
            try {
                Path path = Path.of(OUTPUT).toAbsolutePath().normalize();
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException exception) {
                if (OUTPUT_WARNING.compareAndSet(false, true)) {
                    Erydon.LOGGER.warn("[{}] Unable to write Synapheia metrics to {}.",
                            Erydon.MOD_ID, OUTPUT, exception);
                }
            }
        }
    }
}
