package com.oliver.erydon.client.pom;

import com.oliver.erydon.Erydon;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-switch rollback and development-mode selection for the optional adapter. */
public final class ErydonCuPomAdapterConfig {
    public static final String MODE_PROPERTY = "erydon.cuPom.mode";
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    public static ComplementaryUnboundDev5SourceTransformer.Mode configuredMode() {
        Selection selection = select(
                System.getProperty(MODE_PROPERTY, "auto"),
                FabricLoader.getInstance().isDevelopmentEnvironment());
        if (selection.warning() != null && WARNED.compareAndSet(false, true)) {
            Erydon.LOGGER.warn("[{}] {}", Erydon.MOD_ID, selection.warning());
        }
        return selection.mode();
    }

    static Selection select(String rawMode, boolean developmentEnvironment) {
        String normalized = rawMode == null ? "auto" : rawMode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "off" -> new Selection(ComplementaryUnboundDev5SourceTransformer.Mode.OFF, null);
            case "force" -> developmentEnvironment
                    ? new Selection(ComplementaryUnboundDev5SourceTransformer.Mode.FORCE, null)
                    : new Selection(ComplementaryUnboundDev5SourceTransformer.Mode.AUTO,
                    "Ignoring development-only CTM-POM force mode; using auto.");
            case "auto" -> new Selection(ComplementaryUnboundDev5SourceTransformer.Mode.AUTO, null);
            default -> new Selection(ComplementaryUnboundDev5SourceTransformer.Mode.AUTO,
                    "Unknown CTM-POM mode '" + rawMode + "'; using auto.");
        };
    }

    record Selection(ComplementaryUnboundDev5SourceTransformer.Mode mode, String warning) {
    }

    private ErydonCuPomAdapterConfig() {
    }
}
