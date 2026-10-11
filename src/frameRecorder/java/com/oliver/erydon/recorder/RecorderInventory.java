package com.oliver.erydon.recorder;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import org.lwjgl.opengl.GL11;

/** Called only on the render thread, before/after timed sampling. No filesystem access. */
final class RecorderInventory {
    static Map<String, Object> configuration(MinecraftClient client) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        var mods = new ArrayList<Map<String, String>>();
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            mods.add(Map.of("id", mod.getMetadata().getId(), "version", mod.getMetadata().getVersion().getFriendlyString()));
        }
        mods.sort(java.util.Comparator.comparing(item -> item.get("id")));
        result.put("mods", List.copyOf(mods));
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("gpu", GL11.glGetString(GL11.GL_RENDERER));
        result.put("driver", GL11.glGetString(GL11.GL_VERSION));
        result.put("enabledResourcePacks", List.copyOf(client.getResourcePackManager().getEnabledNames()));
        result.put("options", optionValues(client));
        result.put("framebuffer", List.of(client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight()));
        result.put("vsync", client.options.getEnableVsync().getValue());
        result.put("fpsCap", client.options.getMaxFps().getValue());
        result.put("world", client.getServer() == null ? "multiplayer" : client.getServer().getSaveProperties().getLevelName());
        result.put("metric", "Completed CPU frame wall-clock intervals, including swapBuffers, limiter and intervening work");
        result.put("gpuTimingAvailable", false);
        return result;
    }

    private static Map<String, Object> optionValues(MinecraftClient client) throws Exception {
        var values = new TreeMap<String, Object>();
        for (Field field : client.options.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(client.options);
            if (value instanceof SimpleOption<?> option) value = option.getValue();
            if (value instanceof Number || value instanceof Boolean || value instanceof String)
                values.put(field.getName(), value);
            else if (value instanceof Enum<?> enumeration) values.put(field.getName(), enumeration.name());
        }
        return values;
    }

    static Map<String, Object> scene(MinecraftClient client) {
        var camera = client.gameRenderer.getCamera();
        var position = camera.getPos();
        var result = new LinkedHashMap<String, Object>();
        result.put("camera", List.of(position.x, position.y, position.z, camera.getYaw(), camera.getPitch()));
        result.put("dimension", client.world.getRegistryKey().getValue().toString());
        result.put("worldTime", client.world.getTime());
        result.put("timeOfDay", client.world.getTimeOfDay());
        result.put("rain", client.world.getRainGradient(1));
        result.put("thunder", client.world.getThunderGradient(1));
        result.put("chunkDebug", client.worldRenderer.getChunksDebugString());
        result.put("focused", client.isWindowFocused());
        result.put("terrainGuard", "Vanilla completion plus supported Sodium worker/result/rebuild queues when present");
        result.put("worldLightingFrozen", false);
        return result;
    }
}
