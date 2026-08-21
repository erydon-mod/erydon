package com.oliver.erydon;

import com.oliver.erydon.client.ErydonConfigNetworkingClient;
import com.oliver.erydon.client.CollectionResourcePackNotice;
import com.oliver.erydon.client.model.CoverModelLoadingPlugin;
import com.oliver.erydon.client.model.ErydonCtmService;
import com.oliver.erydon.client.model.ErydonFamilyModelLoadingPlugin;
import com.oliver.erydon.client.model.ErydonModelPerformanceProbePlugin;
import com.oliver.erydon.client.model.ErydonRawModelLoadingPlugin;
import com.oliver.erydon.client.model.ErydonSharedBakedGeometryPlugin;
import com.oliver.erydon.client.model.ErydonSlopeModelLoadingPlugin;
import com.oliver.erydon.client.model.GothicArchCtmModelLoadingPlugin;
import com.oliver.erydon.client.model.ModernArchCtmModelLoadingPlugin;
import com.oliver.erydon.client.model.SynapheiaModelLoadingPlugin;
import com.oliver.erydon.client.profile.ErydonLoadProfiler;
import com.oliver.erydon.client.tooltip.ErydonTooltipClient;
import com.oliver.erydon.block.AlcoveBlock;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.registry.Registries;



public final class ErydonClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ErydonConfigNetworkingClient.register();
        CollectionResourcePackNotice.register();
        // Ã¢Å“â€¦ REQUIRED: register model plugin manually
        ModelLoadingPlugin.register(new CoverModelLoadingPlugin());
        if (Boolean.getBoolean("erydon.debug.disable_raw_model_loader")) {
            Erydon.LOGGER.warn("[{}] Raw authoring model loader disabled by debug flag.", Erydon.MOD_ID);
        } else {
            PreparableModelLoadingPlugin.register(ErydonRawModelLoadingPlugin::load, new ErydonRawModelLoadingPlugin());
        }
        ModelLoadingPlugin.register(new ErydonSharedBakedGeometryPlugin());
        ModelLoadingPlugin.register(new ErydonFamilyModelLoadingPlugin());
        ModelLoadingPlugin.register(new ErydonSlopeModelLoadingPlugin());
        ModelLoadingPlugin.register(new GothicArchCtmModelLoadingPlugin());
        ModelLoadingPlugin.register(new ModernArchCtmModelLoadingPlugin());
        SynapheiaModelLoadingPlugin.register();
        ErydonCtmService.registerReloadListener();
        if (ErydonLoadProfiler.isEnabled()) {
            ModelLoadingPlugin.register(new ErydonModelPerformanceProbePlugin());
            ErydonLoadProfiler.registerClientReloadListener();
        }
        registerSharedGeometryBenchmarkHarness();
        ErydonTooltipClient.init();

        BlockRenderLayerMap.INSTANCE.putBlocks(RenderLayer.getTranslucent(),
                ModBlocks.AGANITE_BRAZIER,
                ModBlocks.BOREALIS_BRAZIER,
                ModBlocks.BRECTITE_BRAZIER,
                ModBlocks.CALACATTUM_BRAZIER,
                ModBlocks.CHALSTROM_BRAZIER,
                ModBlocks.CHRYSONYX_BRAZIER,
                ModBlocks.ETRUSCUS_BRAZIER,
                ModBlocks.GELASTRUM_BRAZIER,
                ModBlocks.GLACIUM_BRAZIER,
                ModBlocks.HESPERION_BRAZIER,
                ModBlocks.IMPERIUM_BRAZIER,
                ModBlocks.KYLORION_BRAZIER,
                ModBlocks.LAURENTIUM_BRAZIER,
                ModBlocks.MIELONYX_BRAZIER,
                ModBlocks.NERIUM_BRAZIER,
                ModBlocks.NOXOPLIS_BRAZIER,
                ModBlocks.PORTORIUM_BRAZIER,
                ModBlocks.ROSINIUM_BRAZIER,
                ModBlocks.SANGUENITE_BRAZIER,
                ModBlocks.SOLISTRA_BRAZIER,
                ModBlocks.PORPHYROS_BRAZIER,
                ModBlocks.SELENEPHOS_BRAZIER,
                ModBlocks.STRIATUS_BRAZIER,
                ModBlocks.ATERZON_BRAZIER,
                ModBlocks.LATMION_BRAZIER,
                ModBlocks.KELASTRION_BRAZIER,
                ModBlocks.PSAMATHEON_BRAZIER,
                ModBlocks.AGANITE_OIL_BURNER,
                ModBlocks.BOREALIS_OIL_BURNER,
                ModBlocks.BRECTITE_OIL_BURNER,
                ModBlocks.CALACATTUM_OIL_BURNER,
                ModBlocks.CHALSTROM_OIL_BURNER,
                ModBlocks.CHRYSONYX_OIL_BURNER,
                ModBlocks.ETRUSCUS_OIL_BURNER,
                ModBlocks.GELASTRUM_OIL_BURNER,
                ModBlocks.GLACIUM_OIL_BURNER,
                ModBlocks.HESPERION_OIL_BURNER,
                ModBlocks.IMPERIUM_OIL_BURNER,
                ModBlocks.KYLORION_OIL_BURNER,
                ModBlocks.LAURENTIUM_OIL_BURNER,
                ModBlocks.MIELONYX_OIL_BURNER,
                ModBlocks.NERIUM_OIL_BURNER,
                ModBlocks.NOXOPLIS_OIL_BURNER,
                ModBlocks.PORTORIUM_OIL_BURNER,
                ModBlocks.ROSINIUM_OIL_BURNER,
                ModBlocks.SANGUENITE_OIL_BURNER,
                ModBlocks.SOLISTRA_OIL_BURNER,
                ModBlocks.PORPHYROS_OIL_BURNER,
                ModBlocks.SELENEPHOS_OIL_BURNER,
                ModBlocks.STRIATUS_OIL_BURNER,
                ModBlocks.ATERZON_OIL_BURNER,
                ModBlocks.LATMION_OIL_BURNER,
                ModBlocks.KELASTRION_OIL_BURNER,
                ModBlocks.PSAMATHEON_OIL_BURNER
        );

        // glazing layers
        BlockRenderLayerMap.INSTANCE.putBlocks(RenderLayer.getTranslucent(),
                ModBlocks.GLAZING_TINTED,
                ModBlocks.GLAZING_SILVER,
                ModBlocks.GLAZING_CRYSTAL,
                ModBlocks.GLAZING_BRONZE,
                ModBlocks.GLAZING_TINTED_BLOCK,
                ModBlocks.GLAZING_SILVER_BLOCK,
                ModBlocks.GLAZING_CRYSTAL_BLOCK,
                ModBlocks.GLAZING_BRONZE_BLOCK,
                ModBlocks.GLAZING_TINTED_LAYER_VERTICAL,
                ModBlocks.GLAZING_SILVER_LAYER_VERTICAL,
                ModBlocks.GLAZING_CRYSTAL_LAYER_VERTICAL,
                ModBlocks.GLAZING_BRONZE_LAYER_VERTICAL,
                ModBlocks.GLAZING_FRAMED_TINTED_SHALLOW_SLOPE_LOWER,
                ModBlocks.GLAZING_FRAMED_TINTED_SHALLOW_SLOPE_UPPER,
                ModBlocks.GLAZING_FRAMED_SILVER_SHALLOW_SLOPE_LOWER,
                ModBlocks.GLAZING_FRAMED_SILVER_SHALLOW_SLOPE_UPPER,
                ModBlocks.GLAZING_FRAMED_CRYSTAL_SHALLOW_SLOPE_LOWER,
                ModBlocks.GLAZING_FRAMED_CRYSTAL_SHALLOW_SLOPE_UPPER,
                ModBlocks.GLAZING_FRAMED_BRONZE_SHALLOW_SLOPE_LOWER,
                ModBlocks.GLAZING_FRAMED_BRONZE_SHALLOW_SLOPE_UPPER,
                ModBlocks.GLAZING_FRAMED_TINTED_SLOPE,
                ModBlocks.GLAZING_FRAMED_SILVER_SLOPE,
                ModBlocks.GLAZING_FRAMED_CRYSTAL_SLOPE,
                ModBlocks.GLAZING_FRAMED_BRONZE_SLOPE,
                ModBlocks.GLAZING_FRAMED_TINTED_SLOPE_VERTICAL,
                ModBlocks.GLAZING_FRAMED_SILVER_SLOPE_VERTICAL,
                ModBlocks.GLAZING_FRAMED_CRYSTAL_SLOPE_VERTICAL,
                ModBlocks.GLAZING_FRAMED_BRONZE_SLOPE_VERTICAL,
                ModBlocks.GLAZING_FRAMED_TINTED,
                ModBlocks.GLAZING_FRAMED_SILVER,
                ModBlocks.GLAZING_FRAMED_CRYSTAL,
                ModBlocks.GLAZING_FRAMED_BRONZE
        );
        BlockRenderLayerMap.INSTANCE.putBlocks(RenderLayer.getTranslucent(),
                ModBlocks.SELENEPHOS_DIAPHANES,
                ModBlocks.SELENEPHOS_DIAPHANES_BLOCK,
                ModBlocks.SELENEPHOS_DIAPHANES_LAYER_VERTICAL,
                ModBlocks.SELENEPHOS_DIAPHANES_LAYER,
                ModBlocks.SELENEPHOS_DIAPHANES_SLAB
        );
        BlockRenderLayerMap.INSTANCE.putBlocks(RenderLayer.getTranslucent(),
                ModBlocks.BOREALIS_DIAPHANES,
                ModBlocks.BOREALIS_DIAPHANES_BLOCK,
                ModBlocks.BOREALIS_DIAPHANES_LAYER_VERTICAL,
                ModBlocks.BOREALIS_DIAPHANES_LAYER,
                ModBlocks.BOREALIS_DIAPHANES_SLAB,
                ModBlocks.GELASTRUM_DIAPHANES,
                ModBlocks.GELASTRUM_DIAPHANES_BLOCK,
                ModBlocks.GELASTRUM_DIAPHANES_LAYER_VERTICAL,
                ModBlocks.GELASTRUM_DIAPHANES_LAYER,
                ModBlocks.GELASTRUM_DIAPHANES_SLAB,
                ModBlocks.MIELONYX_DIAPHANES,
                ModBlocks.MIELONYX_DIAPHANES_BLOCK,
                ModBlocks.MIELONYX_DIAPHANES_LAYER_VERTICAL,
                ModBlocks.MIELONYX_DIAPHANES_LAYER,
                ModBlocks.MIELONYX_DIAPHANES_SLAB
        );
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.AGANITE_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.AGANITE_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ATERZON_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ATERZON_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BOREALIS_DIAPHANES_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BOREALIS_DIAPHANES_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BOREALIS_DIAPHANES_LIGHT_PENDANT, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BOREALIS_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BOREALIS_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BRECTITE_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BRECTITE_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CALACATTUM_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CALACATTUM_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHALSTROM_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHALSTROM_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHRYSONYX_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHRYSONYX_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORPHYROS_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORPHYROS_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ETRUSCUS_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ETRUSCUS_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GELASTRUM_DIAPHANES_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GELASTRUM_DIAPHANES_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GELASTRUM_DIAPHANES_LIGHT_PENDANT, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GELASTRUM_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GELASTRUM_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GLACIUM_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GLACIUM_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.HESPERION_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.HESPERION_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.IMPERIUM_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.IMPERIUM_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KYLORION_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KYLORION_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LAURENTIUM_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LAURENTIUM_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.MIELONYX_DIAPHANES_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.MIELONYX_DIAPHANES_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.MIELONYX_DIAPHANES_LIGHT_PENDANT, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.MIELONYX_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.MIELONYX_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NERIUM_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NERIUM_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NOXOPLIS_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NOXOPLIS_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORTORIUM_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORTORIUM_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ROSINIUM_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ROSINIUM_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SANGUENITE_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SANGUENITE_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SELENEPHOS_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SELENEPHOS_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SELENEPHOS_DIAPHANES_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SELENEPHOS_DIAPHANES_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SELENEPHOS_DIAPHANES_LIGHT_PENDANT, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SOLISTRA_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SOLISTRA_LIGHT_WALL, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.STRIATUS_LIGHT_MODERN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.STRIATUS_LIGHT_WALL, RenderLayer.getTranslucent());


        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.AGANITE_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.AGANITE_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ATERZON_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ATERZON_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BOREALIS_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BOREALIS_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BRECTITE_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BRECTITE_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CALACATTUM_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CALACATTUM_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHALSTROM_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHALSTROM_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHRYSONYX_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHRYSONYX_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORPHYROS_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORPHYROS_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ETRUSCUS_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ETRUSCUS_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GELASTRUM_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GELASTRUM_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GLACIUM_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GLACIUM_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.HESPERION_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.HESPERION_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.IMPERIUM_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.IMPERIUM_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KELASTRION_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KELASTRION_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KYLORION_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KYLORION_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LATMION_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LATMION_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LAURENTIUM_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LAURENTIUM_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.MIELONYX_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.MIELONYX_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NERIUM_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NERIUM_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NOXOPLIS_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NOXOPLIS_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORTORIUM_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORTORIUM_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PSAMATHEON_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PSAMATHEON_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ROSINIUM_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ROSINIUM_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SANGUENITE_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SANGUENITE_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SELENEPHOS_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SELENEPHOS_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SOLISTRA_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SOLISTRA_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.STRIATUS_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.STRIATUS_WINDOW_ARCH_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.AGANITE_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BOREALIS_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BRECTITE_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CALACATTUM_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHALSTROM_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHRYSONYX_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ETRUSCUS_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GELASTRUM_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GLACIUM_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.HESPERION_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.IMPERIUM_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KELASTRION_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KYLORION_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LATMION_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LAURENTIUM_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.MIELONYX_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NERIUM_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NOXOPLIS_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORTORIUM_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PSAMATHEON_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ROSINIUM_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SANGUENITE_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SOLISTRA_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.STRIATUS_ASHLAR_WINDOW_ARCH, RenderLayer.getTranslucent());

        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.AGANITE_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.AGANITE_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ATERZON_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ATERZON_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BOREALIS_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BOREALIS_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BRECTITE_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BRECTITE_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CALACATTUM_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CALACATTUM_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHALSTROM_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHALSTROM_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHRYSONYX_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHRYSONYX_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORPHYROS_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORPHYROS_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ETRUSCUS_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ETRUSCUS_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GELASTRUM_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GELASTRUM_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GLACIUM_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GLACIUM_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.HESPERION_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.HESPERION_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.IMPERIUM_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.IMPERIUM_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KELASTRION_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KELASTRION_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KYLORION_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KYLORION_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LATMION_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LATMION_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LAURENTIUM_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LAURENTIUM_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.MIELONYX_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.MIELONYX_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NERIUM_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NERIUM_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NOXOPLIS_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NOXOPLIS_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORTORIUM_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORTORIUM_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PSAMATHEON_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PSAMATHEON_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ROSINIUM_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ROSINIUM_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SANGUENITE_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SANGUENITE_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SELENEPHOS_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SELENEPHOS_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SOLISTRA_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SOLISTRA_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.STRIATUS_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.STRIATUS_WINDOW_FRENCH_GEORGIAN_AGED, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.AGANITE_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BOREALIS_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BRECTITE_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CALACATTUM_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHALSTROM_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.CHRYSONYX_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ETRUSCUS_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GELASTRUM_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GLACIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.HESPERION_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.IMPERIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KELASTRION_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.KYLORION_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LATMION_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LAURENTIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.MIELONYX_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NERIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.NOXOPLIS_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PORTORIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.PSAMATHEON_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ROSINIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SANGUENITE_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.SOLISTRA_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.STRIATUS_ASHLAR_WINDOW_FRENCH_GEORGIAN, RenderLayer.getTranslucent());


        ColorProviderRegistry.BLOCK.register((state, world, pos, tintIndex) -> {
            if (tintIndex != 0) return -1;
            return 0xFFFFFF;
        },

                ModBlocks.AGANITE_WINDOW_ARCH, ModBlocks.AGANITE_WINDOW_ARCH_AGED,
                ModBlocks.ATERZON_WINDOW_ARCH, ModBlocks.ATERZON_WINDOW_ARCH_AGED,
                ModBlocks.BOREALIS_WINDOW_ARCH, ModBlocks.BOREALIS_WINDOW_ARCH_AGED,
                ModBlocks.BRECTITE_WINDOW_ARCH, ModBlocks.BRECTITE_WINDOW_ARCH_AGED,
                ModBlocks.CALACATTUM_WINDOW_ARCH, ModBlocks.CALACATTUM_WINDOW_ARCH_AGED,
                ModBlocks.CHALSTROM_WINDOW_ARCH, ModBlocks.CHALSTROM_WINDOW_ARCH_AGED,
                ModBlocks.CHRYSONYX_WINDOW_ARCH, ModBlocks.CHRYSONYX_WINDOW_ARCH_AGED,
                ModBlocks.PORPHYROS_WINDOW_ARCH, ModBlocks.PORPHYROS_WINDOW_ARCH_AGED,
                ModBlocks.ETRUSCUS_WINDOW_ARCH, ModBlocks.ETRUSCUS_WINDOW_ARCH_AGED,
                ModBlocks.GELASTRUM_WINDOW_ARCH, ModBlocks.GELASTRUM_WINDOW_ARCH_AGED,
                ModBlocks.GLACIUM_WINDOW_ARCH, ModBlocks.GLACIUM_WINDOW_ARCH_AGED,
                ModBlocks.HESPERION_WINDOW_ARCH, ModBlocks.HESPERION_WINDOW_ARCH_AGED,
                ModBlocks.IMPERIUM_WINDOW_ARCH, ModBlocks.IMPERIUM_WINDOW_ARCH_AGED,
                ModBlocks.KELASTRION_WINDOW_ARCH, ModBlocks.KELASTRION_WINDOW_ARCH_AGED,
                ModBlocks.KYLORION_WINDOW_ARCH, ModBlocks.KYLORION_WINDOW_ARCH_AGED,
                ModBlocks.LATMION_WINDOW_ARCH, ModBlocks.LATMION_WINDOW_ARCH_AGED,
                ModBlocks.LAURENTIUM_WINDOW_ARCH, ModBlocks.LAURENTIUM_WINDOW_ARCH_AGED,
                ModBlocks.MIELONYX_WINDOW_ARCH, ModBlocks.MIELONYX_WINDOW_ARCH_AGED,
                ModBlocks.NERIUM_WINDOW_ARCH, ModBlocks.NERIUM_WINDOW_ARCH_AGED,
                ModBlocks.NOXOPLIS_WINDOW_ARCH, ModBlocks.NOXOPLIS_WINDOW_ARCH_AGED,
                ModBlocks.PORTORIUM_WINDOW_ARCH, ModBlocks.PORTORIUM_WINDOW_ARCH_AGED,
                ModBlocks.PSAMATHEON_WINDOW_ARCH, ModBlocks.PSAMATHEON_WINDOW_ARCH_AGED,
                ModBlocks.ROSINIUM_WINDOW_ARCH, ModBlocks.ROSINIUM_WINDOW_ARCH_AGED,
                ModBlocks.SANGUENITE_WINDOW_ARCH, ModBlocks.SANGUENITE_WINDOW_ARCH_AGED,
                ModBlocks.SELENEPHOS_WINDOW_ARCH, ModBlocks.SELENEPHOS_WINDOW_ARCH_AGED,
                ModBlocks.SOLISTRA_WINDOW_ARCH, ModBlocks.SOLISTRA_WINDOW_ARCH_AGED,
                ModBlocks.STRIATUS_WINDOW_ARCH, ModBlocks.STRIATUS_WINDOW_ARCH_AGED,
                ModBlocks.AGANITE_ASHLAR_WINDOW_ARCH,
                ModBlocks.BOREALIS_ASHLAR_WINDOW_ARCH,
                ModBlocks.BRECTITE_ASHLAR_WINDOW_ARCH,
                ModBlocks.CALACATTUM_ASHLAR_WINDOW_ARCH,
                ModBlocks.CHALSTROM_ASHLAR_WINDOW_ARCH,
                ModBlocks.CHRYSONYX_ASHLAR_WINDOW_ARCH,
                ModBlocks.ETRUSCUS_ASHLAR_WINDOW_ARCH,
                ModBlocks.GELASTRUM_ASHLAR_WINDOW_ARCH,
                ModBlocks.GLACIUM_ASHLAR_WINDOW_ARCH,
                ModBlocks.HESPERION_ASHLAR_WINDOW_ARCH,
                ModBlocks.IMPERIUM_ASHLAR_WINDOW_ARCH,
                ModBlocks.KELASTRION_ASHLAR_WINDOW_ARCH,
                ModBlocks.KYLORION_ASHLAR_WINDOW_ARCH,
                ModBlocks.LATMION_ASHLAR_WINDOW_ARCH,
                ModBlocks.LAURENTIUM_ASHLAR_WINDOW_ARCH,
                ModBlocks.MIELONYX_ASHLAR_WINDOW_ARCH,
                ModBlocks.NERIUM_ASHLAR_WINDOW_ARCH,
                ModBlocks.NOXOPLIS_ASHLAR_WINDOW_ARCH,
                ModBlocks.PORTORIUM_ASHLAR_WINDOW_ARCH,
                ModBlocks.PSAMATHEON_ASHLAR_WINDOW_ARCH,
                ModBlocks.ROSINIUM_ASHLAR_WINDOW_ARCH,
                ModBlocks.SANGUENITE_ASHLAR_WINDOW_ARCH,
                ModBlocks.SOLISTRA_ASHLAR_WINDOW_ARCH,
                ModBlocks.STRIATUS_ASHLAR_WINDOW_ARCH,
                ModBlocks.AGANITE_WINDOW_FRENCH_GEORGIAN, ModBlocks.AGANITE_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.ATERZON_WINDOW_FRENCH_GEORGIAN, ModBlocks.ATERZON_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.BOREALIS_WINDOW_FRENCH_GEORGIAN, ModBlocks.BOREALIS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.BRECTITE_WINDOW_FRENCH_GEORGIAN, ModBlocks.BRECTITE_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.CALACATTUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.CALACATTUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.CHALSTROM_WINDOW_FRENCH_GEORGIAN, ModBlocks.CHALSTROM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.CHRYSONYX_WINDOW_FRENCH_GEORGIAN, ModBlocks.CHRYSONYX_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.PORPHYROS_WINDOW_FRENCH_GEORGIAN, ModBlocks.PORPHYROS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.ETRUSCUS_WINDOW_FRENCH_GEORGIAN, ModBlocks.ETRUSCUS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.GELASTRUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.GELASTRUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.GLACIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.GLACIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.HESPERION_WINDOW_FRENCH_GEORGIAN, ModBlocks.HESPERION_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.IMPERIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.IMPERIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.KELASTRION_WINDOW_FRENCH_GEORGIAN, ModBlocks.KELASTRION_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.KYLORION_WINDOW_FRENCH_GEORGIAN, ModBlocks.KYLORION_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.LATMION_WINDOW_FRENCH_GEORGIAN, ModBlocks.LATMION_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.LAURENTIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.LAURENTIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.MIELONYX_WINDOW_FRENCH_GEORGIAN, ModBlocks.MIELONYX_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.NERIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.NERIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.NOXOPLIS_WINDOW_FRENCH_GEORGIAN, ModBlocks.NOXOPLIS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.PORTORIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.PORTORIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.PSAMATHEON_WINDOW_FRENCH_GEORGIAN, ModBlocks.PSAMATHEON_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.ROSINIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.ROSINIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.SANGUENITE_WINDOW_FRENCH_GEORGIAN, ModBlocks.SANGUENITE_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.SELENEPHOS_WINDOW_FRENCH_GEORGIAN, ModBlocks.SELENEPHOS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.SOLISTRA_WINDOW_FRENCH_GEORGIAN, ModBlocks.SOLISTRA_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.STRIATUS_WINDOW_FRENCH_GEORGIAN, ModBlocks.STRIATUS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.AGANITE_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.BOREALIS_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.BRECTITE_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.CALACATTUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.CHALSTROM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.CHRYSONYX_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.ETRUSCUS_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.GELASTRUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.GLACIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.HESPERION_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.IMPERIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.KELASTRION_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.KYLORION_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.LATMION_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.LAURENTIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.MIELONYX_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.NERIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.NOXOPLIS_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.PORTORIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.PSAMATHEON_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.ROSINIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.SANGUENITE_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.SOLISTRA_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.STRIATUS_ASHLAR_WINDOW_FRENCH_GEORGIAN
        );
        ColorProviderRegistry.BLOCK.register((state, world, pos, tintIndex) -> {
            if (tintIndex != 0) return -1;
            return 0xFFFFFF;
        }, ModBlocks.AGANITE_WINDOW_ARCH, ModBlocks.AGANITE_WINDOW_ARCH_AGED);

        // Item tint now matches the fixed in-world window tint.
        ColorProviderRegistry.ITEM.register((stack, tintIndex) -> {
            if (tintIndex != 0) return -1;
            return 0xFFFFFF;
        },
                ModBlocks.AGANITE_WINDOW_FRENCH_GEORGIAN, ModBlocks.AGANITE_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.ATERZON_WINDOW_FRENCH_GEORGIAN, ModBlocks.ATERZON_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.BOREALIS_WINDOW_FRENCH_GEORGIAN, ModBlocks.BOREALIS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.BRECTITE_WINDOW_FRENCH_GEORGIAN, ModBlocks.BRECTITE_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.CALACATTUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.CALACATTUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.CHALSTROM_WINDOW_FRENCH_GEORGIAN, ModBlocks.CHALSTROM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.CHRYSONYX_WINDOW_FRENCH_GEORGIAN, ModBlocks.CHRYSONYX_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.PORPHYROS_WINDOW_FRENCH_GEORGIAN, ModBlocks.PORPHYROS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.ETRUSCUS_WINDOW_FRENCH_GEORGIAN, ModBlocks.ETRUSCUS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.GELASTRUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.GELASTRUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.GLACIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.GLACIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.HESPERION_WINDOW_FRENCH_GEORGIAN, ModBlocks.HESPERION_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.IMPERIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.IMPERIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.KELASTRION_WINDOW_FRENCH_GEORGIAN, ModBlocks.KELASTRION_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.KYLORION_WINDOW_FRENCH_GEORGIAN, ModBlocks.KYLORION_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.LATMION_WINDOW_FRENCH_GEORGIAN, ModBlocks.LATMION_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.LAURENTIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.LAURENTIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.MIELONYX_WINDOW_FRENCH_GEORGIAN, ModBlocks.MIELONYX_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.NERIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.NERIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.NOXOPLIS_WINDOW_FRENCH_GEORGIAN, ModBlocks.NOXOPLIS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.PORTORIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.PORTORIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.PSAMATHEON_WINDOW_FRENCH_GEORGIAN, ModBlocks.PSAMATHEON_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.ROSINIUM_WINDOW_FRENCH_GEORGIAN, ModBlocks.ROSINIUM_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.SANGUENITE_WINDOW_FRENCH_GEORGIAN, ModBlocks.SANGUENITE_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.SELENEPHOS_WINDOW_FRENCH_GEORGIAN, ModBlocks.SELENEPHOS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.SOLISTRA_WINDOW_FRENCH_GEORGIAN, ModBlocks.SOLISTRA_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.STRIATUS_WINDOW_FRENCH_GEORGIAN, ModBlocks.STRIATUS_WINDOW_FRENCH_GEORGIAN_AGED,
                ModBlocks.AGANITE_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.BOREALIS_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.BRECTITE_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.CALACATTUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.CHALSTROM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.CHRYSONYX_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.ETRUSCUS_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.GELASTRUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.GLACIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.HESPERION_ASHLAR_WINDOW_FRENCH_GEORGIAN, 
                ModBlocks.IMPERIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.KELASTRION_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.KYLORION_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.LATMION_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.LAURENTIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.MIELONYX_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.NERIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.NOXOPLIS_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.PORTORIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.PSAMATHEON_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.ROSINIUM_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.SANGUENITE_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.SOLISTRA_ASHLAR_WINDOW_FRENCH_GEORGIAN,
                ModBlocks.STRIATUS_ASHLAR_WINDOW_FRENCH_GEORGIAN
        );

        Registries.BLOCK.stream()
                .filter(block -> block instanceof AlcoveBlock
                        && Registries.BLOCK.getId(block).getNamespace().equals(Erydon.MOD_ID))
                .forEach(block -> BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getTranslucent()));
        // square column layer
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.AGANITE_COLUMN_SQUARE, RenderLayer.getCutout());
    }

    private static void registerSharedGeometryBenchmarkHarness() {
        if (!Boolean.getBoolean("erydon.shared_geometry.benchmark")) {
            return;
        }
        try {
            Class.forName("com.oliver.erydon.client.profile.ErydonSharedGeometryBenchmarkHarness")
                    .getMethod("register")
                    .invoke(null);
        } catch (ReflectiveOperationException | LinkageError exception) {
            Erydon.LOGGER.warn(
                    "[{}] Shared-geometry benchmark harness was requested but is unavailable.",
                    Erydon.MOD_ID,
                    exception
            );
        }
    }
}
