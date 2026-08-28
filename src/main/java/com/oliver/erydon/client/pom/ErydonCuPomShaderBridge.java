package com.oliver.erydon.client.pom;

import com.oliver.erydon.Erydon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/** Loads the independently authored GLSL helper shipped inside ERYDON. */
public final class ErydonCuPomShaderBridge {
    private static final String RESOURCE = "/assets/erydon/shaders/include/erydon_cu_pom_bridge.glsl";
    private static final Pattern PREPROCESSOR_DIRECTIVE = Pattern.compile("(?m)^[ \\t]*#");
    private static final Sources SOURCES = loadSources();

    public static String vertexSource() {
        return SOURCES.vertex();
    }

    public static String fragmentSource() {
        return SOURCES.fragment();
    }

    private static Sources loadSources() {
        String source = load();
        if (source.isBlank()) {
            return new Sources("", "");
        }
        try {
            return new Sources(specialize(source, Stage.VERTEX), specialize(source, Stage.FRAGMENT));
        } catch (IllegalArgumentException invalidHelper) {
            Erydon.LOGGER.warn(
                    "[{}] CTM-POM shader helper could not be specialized before Iris parsing; adapter will fail closed.",
                    Erydon.MOD_ID,
                    invalidHelper);
            return new Sources("", "");
        }
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

    /**
     * Resolves ERYDON's two stage guards before source reaches Iris. ProgramSet
     * sources have already passed Iris's preprocessor, so injecting any raw
     * directive at that point makes the whole shader pack fail to load.
     */
    static String specialize(String source, Stage stage) {
        StringBuilder result = new StringBuilder(source.length());
        Deque<Conditional> conditionals = new ArrayDeque<>();
        boolean active = true;
        for (String line : source.split("(?<=\\n)", -1)) {
            String directive = line.strip();
            if (directive.startsWith("#ifdef ")) {
                String macro = directive.substring("#ifdef ".length()).strip();
                boolean condition = switch (macro) {
                    case "ERYDON_CTM_POM_VERTEX_STAGE" -> stage == Stage.VERTEX;
                    case "ERYDON_CTM_POM_FRAGMENT_STAGE" -> stage == Stage.FRAGMENT;
                    default -> throw new IllegalArgumentException("Unsupported helper stage guard: " + macro);
                };
                conditionals.push(new Conditional(active, condition));
                active = active && condition;
            } else if (directive.equals("#else")) {
                if (conditionals.isEmpty() || conditionals.peek().elseSeen) {
                    throw new IllegalArgumentException("Unmatched or duplicate #else in CTM-POM helper.");
                }
                Conditional current = conditionals.pop();
                conditionals.push(new Conditional(current.parentActive, current.condition, true));
                active = current.parentActive && !current.condition;
            } else if (directive.equals("#endif")) {
                if (conditionals.isEmpty()) {
                    throw new IllegalArgumentException("Unmatched #endif in CTM-POM helper.");
                }
                active = conditionals.pop().parentActive;
            } else if (active) {
                result.append(line);
            }
        }
        if (!conditionals.isEmpty()) {
            throw new IllegalArgumentException("Unclosed stage guard in CTM-POM helper.");
        }
        String specialized = result.toString();
        if (PREPROCESSOR_DIRECTIVE.matcher(specialized).find()) {
            throw new IllegalArgumentException("A preprocessor directive remained after stage specialization.");
        }
        return specialized;
    }

    enum Stage {
        VERTEX,
        FRAGMENT
    }

    private record Sources(String vertex, String fragment) {
    }

    private static final class Conditional {
        private final boolean parentActive;
        private final boolean condition;
        private final boolean elseSeen;

        private Conditional(boolean parentActive, boolean condition) {
            this(parentActive, condition, false);
        }

        private Conditional(boolean parentActive, boolean condition, boolean elseSeen) {
            this.parentActive = parentActive;
            this.condition = condition;
            this.elseSeen = elseSeen;
        }
    }

    private ErydonCuPomShaderBridge() {
    }
}
