package com.oliver.erydon.recorder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;

/** An intentionally small data-only protocol. Requests cannot specify paths or commands. */
public record CaptureRequest(String id, int warmupSeconds, int captureSeconds, int timeoutSeconds) {
    private static final Set<String> FIELDS = Set.of("id", "warmupSeconds", "captureSeconds", "timeoutSeconds");

    public CaptureRequest {
        if (!safeId(id)) throw new IllegalArgumentException("Invalid capture id");
        if (warmupSeconds < 2 || warmupSeconds > 60) throw new IllegalArgumentException("warmupSeconds must be 2..60");
        if (captureSeconds < 1 || captureSeconds > 120) throw new IllegalArgumentException("captureSeconds must be 1..120");
        if (timeoutSeconds < warmupSeconds + captureSeconds + 5 || timeoutSeconds > 600)
            throw new IllegalArgumentException("timeoutSeconds must allow warmup + capture + 5, and be <=600");
    }

    public static boolean safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")
                && !id.matches("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])");
    }

    public static CaptureRequest parse(String json, String filename) {
        if (json.length() > 4096) throw new IllegalArgumentException("Request exceeds 4096 characters");
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        if (!FIELDS.containsAll(object.keySet())) throw new IllegalArgumentException("Unknown request field");
        String id = object.get("id").getAsString();
        if (!(id + ".json").equals(filename)) throw new IllegalArgumentException("Request filename must match id");
        return new CaptureRequest(id, integer(object, "warmupSeconds", 5), integer(object, "captureSeconds", 60),
                integer(object, "timeoutSeconds", 300));
    }

    private static int integer(JsonObject object, String name, int fallback) {
        if (!object.has(name)) return fallback;
        var element = object.getAsJsonPrimitive(name);
        if (!element.isNumber() || !element.getAsString().matches("[0-9]{1,3}"))
            throw new IllegalArgumentException(name + " must be an integer");
        return element.getAsInt();
    }
}
