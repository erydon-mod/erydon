package com.oliver.erydon.command;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Optional Daedalon assembly support, without a required companion dependency. */
final class ErydonSwapBlockEntitySupport {
    private static final String FOUNTAIN_CLASS = "com.oliver.daedalon.block.FountainBasinBlock";
    private static final ClassValue<Optional<Method>> REFRESH = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            if (!FOUNTAIN_CLASS.equals(type.getName())) {
                return Optional.empty();
            }
            try {
                return Optional.of(type.getMethod("refreshAssembly", World.class, BlockPos.class, BlockState.class));
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException("Installed Daedalon is missing fountain assembly refresh support", exception);
            }
        }
    };

    private ErydonSwapBlockEntitySupport() {
    }

    static NbtCompound swapComponents(NbtCompound original, UnaryOperator<Identifier> mapId) {
        if (original == null) {
            return null;
        }
        NbtCompound result = original.copy();
        // A fountain's attached plinth is a stored blockstate, not a separate world block.
        // Keep its authored properties and bowl count; change only a valid material ID.
        if ("daedalon:fountain_basin".equals(result.getString("id")) && result.contains("Plinth", 10)) {
            NbtCompound plinth = result.getCompound("Plinth");
            Identifier id = Identifier.tryParse(plinth.getString("Name"));
            if (id != null) {
                plinth.putString("Name", mapId.apply(id).toString());
            }
        }
        return result;
    }

    static void validateRefresh(BlockState state) {
        REFRESH.get(state.getBlock().getClass());
    }

    static void refreshAssembly(ServerWorld world, BlockPos pos, BlockState state) {
        REFRESH.get(state.getBlock().getClass()).ifPresent(method -> {
            try {
                method.invoke(null, world, pos, state);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not refresh swapped Daedalon fountain assembly", exception);
            }
        });
    }
}
