package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.util.ErydonIdNaming;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.Sprite;
import net.minecraft.registry.Registries;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ErydonCtmService {
    private static final ErydonCtmService INSTANCE = new ErydonCtmService();
    private static final Identifier RELOAD_LISTENER_ID = new Identifier(Erydon.MOD_ID, "ctm_service");
    private static final int REPEAT_TILE_COUNT = 36;
    private static final String NO_CTM_SET = "<none>";
    private static final String SPIRAL_STAIR_SUFFIX = "_stairs_spiral_large";
    private static final String MODERN_ARCH_SUFFIX = "_arch_modern";
    private static final String GOTHIC_ARCH_SUFFIX = "_arch_gothic";
    private static final String[] SLOPE_SUFFIXES = {
            "_slope_vertical_shallow_narrow",
            "_slope_vertical_shallow_broad",
            "_slope_vertical",
            "_slope_shallow_lower",
            "_slope_shallow_upper",
            "_slope_steep_lower",
            "_slope_steep_upper",
            "_slope"
    };

    private final Map<String, String> slopeSetNames = new ConcurrentHashMap<>();
    private final Map<String, Sprite[]> repeatSpritesBySet = new ConcurrentHashMap<>();

    private ErydonCtmService() {
    }

    public static ErydonCtmService get(ResourceManager manager) {
        return INSTANCE;
    }

    public static void registerReloadListener() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return RELOAD_LISTENER_ID;
            }

            @Override
            public void reload(ResourceManager manager) {
                INSTANCE.clearSpriteCache();
            }
        });
    }

    public boolean handlesSlope(BlockState state) {
        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        return blockId != null
                && Erydon.MOD_ID.equals(blockId.getNamespace())
                && slopeCtmSetName(blockId.getPath()) != null;
    }

    public boolean handlesModelId(Identifier id) {
        return ErydonSlopeModelClassifier.isHandledSlopeId(id);
    }

    public String slopeCtmSetName(String blockPath) {
        if (blockPath == null) {
            return null;
        }

        String cached = slopeSetNames.computeIfAbsent(blockPath, ErydonCtmService::resolveSlopeCtmSetName);
        return NO_CTM_SET.equals(cached) ? null : cached;
    }

    public String spiralCtmSetName(String blockPath) {
        if (blockPath == null) {
            return null;
        }
        boolean aged = ErydonIdNaming.isAged(blockPath);
        String basePath = ErydonIdNaming.withoutAged(blockPath);
        if (!basePath.endsWith(SPIRAL_STAIR_SUFFIX)) {
            return null;
        }
        String ctmSet = basePath.substring(0, basePath.length() - SPIRAL_STAIR_SUFFIX.length());
        return aged ? ctmSet + "_aged" : ctmSet;
    }

    public String gothicArchCtmSetName(String blockPath) {
        return archCtmSetName(blockPath, GOTHIC_ARCH_SUFFIX);
    }

    public String modernArchCtmSetName(String blockPath) {
        return archCtmSetName(blockPath, MODERN_ARCH_SUFFIX);
    }

    private static String archCtmSetName(String blockPath, String suffix) {
        if (blockPath == null) {
            return null;
        }
        boolean aged = ErydonIdNaming.isAged(blockPath);
        String basePath = ErydonIdNaming.withoutAged(blockPath);
        if (!basePath.endsWith(suffix)) {
            return null;
        }
        String ctmSet = basePath.substring(0, basePath.length() - suffix.length());
        return aged ? ctmSet + "_aged" : ctmSet;
    }

    private static String resolveSlopeCtmSetName(String blockPath) {
        if (ErydonSlopeModelClassifier.isGlazingPath(blockPath)) {
            return NO_CTM_SET;
        }

        boolean aged = ErydonIdNaming.isAged(blockPath);
        String basePath = ErydonIdNaming.withoutAged(blockPath);

        for (String suffix : SLOPE_SUFFIXES) {
            if (basePath.endsWith(suffix)) {
                String ctmSet = basePath.substring(0, basePath.length() - suffix.length());
                return aged ? ctmSet + "_aged" : ctmSet;
            }
        }

        return NO_CTM_SET;
    }

    public Sprite resolveSlopeRepeatSprite(BlockState state,
                                           BlockPos pos,
                                           Direction face,
                                           String baseSetName) {
        return repeatSprites(baseSetName)[repeatTileIndex(pos, face)];
    }

    Sprite[] repeatSprites(String baseSetName) {
        return repeatSpritesBySet.computeIfAbsent(baseSetName, ErydonCtmService::loadRepeatSprites);
    }

    private static Sprite[] loadRepeatSprites(String baseSetName) {
        var atlas = MinecraftClient.getInstance().getSpriteAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
        Sprite[] sprites = new Sprite[REPEAT_TILE_COUNT];
        for (int index = 0; index < REPEAT_TILE_COUNT; index++) {
            Identifier spriteId = new Identifier("minecraft", "optifine/ctm/" + baseSetName + "/" + index);
            sprites[index] = atlas.apply(spriteId);
        }
        return sprites;
    }

    private void clearSpriteCache() {
        repeatSpritesBySet.clear();
        SpiralStairBakedModel.clearGeometryCache();
        ArchRepeatCtmRenderer.clearGeometryCache();
    }

    private static int repeatTileIndex(BlockPos pos, Direction face) {
        return repeatTileIndex(pos.getX(), pos.getY(), pos.getZ(), face);
    }

    static int repeatTileIndex(int x, int y, int z, Direction face) {
        // Must match Continuity's default RepeatSpriteProvider mapping exactly.
        Direction safeFace = face == null ? Direction.UP : face;
        int tileX;
        int tileY;

        switch (safeFace) {
            case DOWN -> {
                tileX = x;
                tileY = -z - 1;
            }
            case UP -> {
                tileX = x;
                tileY = z;
            }
            case NORTH -> {
                tileX = -x - 1;
                tileY = -y;
            }
            case SOUTH -> {
                tileX = x;
                tileY = -y;
            }
            case WEST -> {
                tileX = z;
                tileY = -y;
            }
            case EAST -> {
                tileX = -z - 1;
                tileY = -y;
            }
            default -> {
                tileX = 0;
                tileY = 0;
            }
        }

        return Math.floorMod(tileY, 6) * 6 + Math.floorMod(tileX, 6);
    }
}
