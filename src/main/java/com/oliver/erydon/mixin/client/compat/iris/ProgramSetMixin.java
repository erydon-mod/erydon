package com.oliver.erydon.mixin.client.compat.iris;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.pom.ComplementaryUnboundDev5SourceTransformer;
import com.oliver.erydon.client.pom.ErydonCuPomShaderBridge;
import com.oliver.erydon.client.pom.ErydonIrisShaderPropertiesExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.concurrent.atomic.AtomicBoolean;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.programs.ProgramSet", remap = false)
public abstract class ProgramSetMixin {
    private static final AtomicBoolean ERYDON$TRANSFORM_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean ERYDON$FAILURE_LOGGED = new AtomicBoolean();

    @ModifyArgs(
            method = "readProgramSource(Lnet/irisshaders/iris/shaderpack/include/AbsolutePackPath;Ljava/util/function/Function;Ljava/lang/String;Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;Lnet/irisshaders/iris/shaderpack/properties/ShaderProperties;Lnet/irisshaders/iris/gl/blending/BlendModeOverride;Z)Lnet/irisshaders/iris/shaderpack/programs/ProgramSource;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/shaderpack/programs/ProgramSource;<init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;Lnet/irisshaders/iris/shaderpack/properties/ShaderProperties;Lnet/irisshaders/iris/gl/blending/BlendModeOverride;)V",
                    remap = false
            ),
            require = 1,
            expect = 1,
            allow = 1,
            remap = false
    )
    private static void erydon$adaptTerrainFragmentAtomically(Args args) {
        Object shaderProperties = args.get(7);
        if (!(shaderProperties instanceof ErydonIrisShaderPropertiesExtension extension)) {
            return;
        }
        String programName = args.get(0);
        String fragmentSource = args.get(5);
        ComplementaryUnboundDev5SourceTransformer.Result result =
                ComplementaryUnboundDev5SourceTransformer.transformFragment(
                        programName,
                        fragmentSource,
                        ErydonCuPomShaderBridge.source(),
                        extension.erydon$isCuPomEligible());
        if (result.changed()) {
            args.set(5, result.text());
            if (ERYDON$TRANSFORM_LOGGED.compareAndSet(false, true)) {
                Erydon.LOGGER.info("[{}] Adapted CU gbuffers_terrain in memory for CTM-aware POM.", Erydon.MOD_ID);
            }
        } else if (("ANCHOR_MISMATCH_NO_CHANGE".equals(result.status())
                || "POSTCONDITION_FAILED_NO_CHANGE".equals(result.status()))
                && ERYDON$FAILURE_LOGGED.compareAndSet(false, true)) {
            Erydon.LOGGER.warn("[{}] CU CTM-POM source adapter failed closed: {} {}",
                    Erydon.MOD_ID, result.status(), result.counts());
        }
    }
}
