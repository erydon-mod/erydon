package com.oliver.erydon.block.entity;

import com.oliver.erydon.ModBlocks;
import com.oliver.erydon.block.CorniceAssemblyBlock;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlockEntities {
    private static BlockEntityType<CorniceAssemblyBlockEntity> corniceAssembly;
    private static CorniceAssemblyBlock assemblyBlock;
    private ModBlockEntities() {
    }

    public static void register() {
        registerCorniceAssembly((CorniceAssemblyBlock) ModBlocks.CORNICE_ASSEMBLY);
    }

    public static void registerCorniceAssembly(CorniceAssemblyBlock block) {
        if (corniceAssembly != null) {
            if (assemblyBlock != block) throw new IllegalStateException("Cornice assembly type already registered");
            return;
        }
        assemblyBlock = block;
        corniceAssembly = Registry.register(Registries.BLOCK_ENTITY_TYPE, new Identifier("erydon", "cornice_assembly"),
                FabricBlockEntityTypeBuilder.create(CorniceAssemblyBlockEntity::new, block).build());
    }

    public static BlockEntityType<CorniceAssemblyBlockEntity> corniceAssembly() { return corniceAssembly; }
    public static CorniceAssemblyBlock corniceAssemblyBlock() {
        if (assemblyBlock == null) throw new IllegalStateException("Cornice assembly not registered");
        return assemblyBlock;
    }
}
