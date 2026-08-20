package com.oliver.erydon.client.pom;

import com.oliver.erydon.Erydon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads the independently authored GLSL helper shipped inside ERYDON. */
public final class ErydonCuPomShaderBridge {
    private static final String RESOURCE = "/assets/erydon/shaders/include/erydon_cu_pom_bridge.glsl";
    private static final String SOURCE = load();

    public static String source() {
        return SOURCE;
    }

    private static String load() {
        try (InputStream input = ErydonCuPomShaderBridge.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                Erydon.LOGGER.warn("[{}] CTM-POM shader helper is missing; adapter will fail closed.", Erydon.MOD_ID);
                return "";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            Erydon.LOGGER.warn("[{}] Could not read CTM-POM shader helper; adapter will fail closed.",
                    Erydon.MOD_ID, exception);
            return "";
        }
    }

    private ErydonCuPomShaderBridge() {
    }
}
