package com.oliver.erydon.client.pom;

import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Optional read-only local shader validation; neither packs nor settings are written. */
final class InstalledShaderTestSupport {
    record Options(boolean pom, int filtering, int normalStrength, boolean temporalAntialiasing) {
        Options(boolean pom, int filtering, int normalStrength) { this(pom, filtering, normalStrength, true); }
        static final Options DEFAULT = new Options(true, 0, 100);
    }

    private static long context;
    private static boolean glfwInitialized;
    private static GLFWErrorCallback errorCallback;
    private static java.lang.reflect.Field irisConfigField;
    private static Object previousIrisConfig;

    static boolean configured() {
        return nonBlank("ERYDON_CU_TEST_SHADER") || nonBlank("ERYDON_CU_TEST_SHADER_DIR");
    }

    private static boolean nonBlank(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    static List<Path> shaderPaths() throws Exception {
        var paths = new LinkedHashSet<Path>();
        if (nonBlank("ERYDON_CU_TEST_SHADER")) {
            Path path = Path.of(System.getenv("ERYDON_CU_TEST_SHADER")).toAbsolutePath().normalize();
            assertTrue(Files.isRegularFile(path), "Missing explicitly selected shader: " + path);
            paths.add(path);
        }
        if (nonBlank("ERYDON_CU_TEST_SHADER_DIR")) {
            Path directory = Path.of(System.getenv("ERYDON_CU_TEST_SHADER_DIR"));
            try (var entries = Files.list(directory)) {
                entries.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().matches("Complementary.*\\.zip"))
                        .map(path -> path.toAbsolutePath().normalize()).sorted().forEach(paths::add);
            }
            assertFalse(paths.isEmpty(), "No Complementary ZIPs in the selected shader directory");
        }
        return List.copyOf(paths);
    }

    static String source(ZipFile zip, String dimension, String file, Options options,
                         List<StringPair> extraDefines) throws Exception {
        return source(zip, dimension, file, options, extraDefines, java.util.Map.of());
    }

    static String source(ZipFile zip, String dimension, String file, Options options,
                         List<StringPair> extraDefines, java.util.Map<String, String> optionOverrides) throws Exception {
        String expanded = HighPolishShaderPackTest.expand(zip, "shaders/" + dimension + "/" + file, 0);
        expanded = option(expanded, "RP_MODE", "3");
        expanded = option(expanded, "ANISOTROPIC_FILTER", Integer.toString(options.filtering()));
        expanded = option(expanded, "NORMAL_MAP_STRENGTH", Integer.toString(options.normalStrength()));
        for (var override : optionOverrides.entrySet()) expanded = option(expanded, override.getKey(), override.getValue());
        // RP_MODE also selects the LabPBR channel layout. Disable only its POM define.
        if (!options.pom()) expanded = expanded.replaceAll("(?m)^[ \\t]*#define POM[ \\t]*$", "");
        if (!options.temporalAntialiasing()) expanded = expanded.replaceAll("(?m)^[ \\t]*#define TAA[ \\t]*$", "");
        var defines = new ArrayList<>(List.of(
                new StringPair("MC_VERSION", "12001"), new StringPair("IS_IRIS", "1"),
                new StringPair("MC_GL_VERSION", "430"), new StringPair("MC_GL_VENDOR_NVIDIA", "1"),
                new StringPair("IRIS_FEATURE_SSBO", "1"), new StringPair("IRIS_FEATURE_CUSTOM_IMAGES", "1")));
        defines.addAll(extraDefines);
        return JcppProcessor.glslPreprocessSource(expanded, defines);
    }

    private static String option(String source, String name, String value) {
        return source.replaceAll("(?m)^([ \\t]*#define " + Pattern.quote(name) + ")[ \\t]+[^ \\t\\r\\n]+",
                "$1 " + Matcher.quoteReplacement(value));
    }

    static boolean driverValidationEnabled() {
        return "true".equalsIgnoreCase(System.getenv("ERYDON_CU_GL_VALIDATE"))
                || "1".equals(System.getenv("ERYDON_CU_GL_VALIDATE"));
    }

    /** Driver compile/link is separate from Iris's syntax parser and requires local graphics. */
    static void validateProgram(String label, String vertex, String fragment) {
        if (!driverValidationEnabled()) return;
        ensureContext();
        // Iris also rewrites legacy GLSL and vertex attributes before driver compilation.
        // CU's AF path, for example, uses inverse() despite its authored #version 130.
        var patched = vertex.contains("mc_Entity")
                ? TransformPatcher.patchSodium(label, vertex, null, null, null, fragment, AlphaTest.ALWAYS,
                    new ShaderAttributeInputs(true, true, false, true, true), new Object2ObjectOpenHashMap<>())
                : TransformPatcher.patchComposite(label, vertex, null, fragment,
                    label.contains("deferred") ? TextureStage.DEFERRED : TextureStage.COMPOSITE_AND_FINAL,
                    new Object2ObjectOpenHashMap<>());
        vertex = patched.get(PatchShaderType.VERTEX);
        fragment = patched.get(PatchShaderType.FRAGMENT);
        int vertexShader = 0;
        int fragmentShader = 0;
        int program = 0;
        try {
            vertexShader = compile(label + " vertex", GL20.GL_VERTEX_SHADER, vertex);
            fragmentShader = compile(label + " fragment", GL20.GL_FRAGMENT_SHADER, fragment);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertexShader);
            GL20.glAttachShader(program, fragmentShader);
            GL20.glLinkProgram(program);
            assertEquals(GL20.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS),
                    label + " link: " + GL20.glGetProgramInfoLog(program));
        } finally {
            if (program != 0) GL20.glDeleteProgram(program);
            if (vertexShader != 0) GL20.glDeleteShader(vertexShader);
            if (fragmentShader != 0) GL20.glDeleteShader(fragmentShader);
            // The matrix never reuses a rendered program. Do not retain hundreds of
            // full sources in Iris's runtime cache merely to inspect each one once.
            try {
                var cache = TransformPatcher.class.getDeclaredField("cache");
                cache.setAccessible(true);
                ((java.util.Map<?, ?>) cache.get(null)).clear();
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot release Iris's test compiler cache", exception);
            }
        }
    }

    private static int compile(String label, int kind, String source) {
        int shader = GL20.glCreateShader(kind);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL20.GL_TRUE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            fail(label + " compile: " + log);
        }
        return shader;
    }

    static void ensureContext() {
        if (context != 0) return;
        try {
            // TransformPatcher reads only the debug flag. Do not initialize/load/save this config.
            irisConfigField = Iris.class.getDeclaredField("irisConfig");
            irisConfigField.setAccessible(true);
            previousIrisConfig = irisConfigField.get(null);
            irisConfigField.set(null, new IrisConfig(Path.of("unused-test-iris.properties")));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot provide Iris's in-memory compiler configuration", exception);
        }
        errorCallback = GLFWErrorCallback.createPrint(System.err).set();
        glfwInitialized = GLFW.glfwInit();
        assertTrue(glfwInitialized, "GLFW initialization failed; driver validation needs a local graphics session");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
        context = GLFW.glfwCreateWindow(1, 1, "ERYDON shader compiler", 0, 0);
        assertNotEquals(0, context, "Could not create an invisible OpenGL 4.3 compatibility context");
        GLFW.glfwMakeContextCurrent(context);
        GL.createCapabilities();
        System.out.println("Invisible shader compiler: " + GL20.glGetString(GL20.GL_VENDOR)
                + " / " + GL20.glGetString(GL20.GL_RENDERER) + " / " + GL20.glGetString(GL20.GL_VERSION));
    }

    static void close() {
        if (context != 0) {
            GL.setCapabilities(null);
            GLFW.glfwMakeContextCurrent(0);
            GLFW.glfwDestroyWindow(context);
            context = 0;
        }
        if (glfwInitialized) GLFW.glfwTerminate();
        glfwInitialized = false;
        if (errorCallback != null) {
            GLFW.glfwSetErrorCallback(null);
            errorCallback.free();
            errorCallback = null;
        }
        if (irisConfigField != null) {
            try {
                irisConfigField.set(null, previousIrisConfig);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            } finally {
                irisConfigField = null;
                previousIrisConfig = null;
            }
        }
    }

    private InstalledShaderTestSupport() { }
}
