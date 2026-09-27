package com.oliver.erydon.mixin.client.compat.iris;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.pom.ComplementaryUnboundDev5SourceTransformer;
import com.oliver.erydon.client.pom.ErydonCuPomShaderBridge;
import com.oliver.erydon.client.pom.ErydonCuPomRuntimeState;
import com.oliver.erydon.client.pom.ErydonIrisShaderPropertiesExtension;
import com.oliver.erydon.client.pom.HighPolishShaderAdapter;
import com.oliver.erydon.client.pom.MetallicShaderAdapter;
import com.oliver.erydon.client.pom.ErydonMetalProgramSetExtension;
import com.oliver.erydon.client.pom.InlaySubstrateTransport;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.programs.ProgramSet", remap = false)
public abstract class ProgramSetMixin implements ErydonMetalProgramSetExtension {
    private static final AtomicBoolean ERYDON$TRANSFORM_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean ERYDON$FAILURE_LOGGED = new AtomicBoolean();
    @Unique private boolean erydon$metalEligible;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/lang/Object;<init>()V",
            shift = At.Shift.AFTER), remap = false, require = 1)
    private void erydon$preflightMetalPrograms(AbsolutePackPath root,
                                              Function<AbsolutePackPath, String> sourceProvider,
                                              ShaderProperties properties, ShaderPack pack, CallbackInfo ci) {
        if (HighPolishShaderAdapter.profile() != HighPolishShaderAdapter.Profile.COMPLEMENTARY) return;
        boolean pom = ((ErydonIrisShaderPropertiesExtension) (Object) properties).erydon$isCuPomEligible();
        boolean recessSupported = false;
        for (String program : MetallicShaderAdapter.PROGRAMS) {
            String vertex = sourceProvider.apply(root.resolve(program + ".vsh"));
            String fragment = sourceProvider.apply(root.resolve(program + ".fsh"));
            // A pack may have an empty base ProgramSet before its dimension overrides.
            if (fragment == null) return;
            var ctm = ComplementaryUnboundDev5SourceTransformer.transformProgram(program, vertex, fragment,
                    ErydonCuPomShaderBridge.vertexSource(), ErydonCuPomShaderBridge.fragmentSource(), pom);
            var polish = HighPolishShaderAdapter.adaptFragment(program, ctm.fragmentText(), true);
            var metal = MetallicShaderAdapter.adapt(program, ctm.vertexText(), polish.text(), true);
            if (!metal.changed()) {
                InlaySubstrateTransport.setSourceSupported(false);
                Erydon.LOGGER.info("[erydon] Metal response retains native rendering for {}: {} {}.", root, program, metal.status());
                return;
            }
            if ("gbuffers_terrain".equals(program)) recessSupported = MetallicShaderAdapter.recessSupported(metal);
        }
        erydon$metalEligible = true;
        InlaySubstrateTransport.setSourceSupported(recessSupported);
        Erydon.LOGGER.info("[erydon] Metal response preflight passed for {} (all eight programs).", root);
        Erydon.LOGGER.info("[erydon] Recessed inlay support for {}: source={}, renderer={}; awaiting terrain compilation.",
                root, recessSupported, InlaySubstrateTransport.rendererSupported());
    }

    @Override public boolean erydon$isMetalEligible() { return erydon$metalEligible; }

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
    private static void erydon$adaptTerrainProgramAtomically(Args args) {
        Object shaderProperties = args.get(7);
        if (!(shaderProperties instanceof ErydonIrisShaderPropertiesExtension extension)) {
            return;
        }
        String programName = args.get(0);
        String vertexSource = args.get(1);
        String fragmentSource = args.get(5);
        ComplementaryUnboundDev5SourceTransformer.ProgramResult result =
                ComplementaryUnboundDev5SourceTransformer.transformProgram(
                        programName,
                        vertexSource,
                        fragmentSource,
                        ErydonCuPomShaderBridge.vertexSource(),
                        ErydonCuPomShaderBridge.fragmentSource(),
                        extension.erydon$isCuPomEligible());
        if ("gbuffers_terrain".equals(programName)) {
            ErydonCuPomRuntimeState.acceptProgramStatus(result.status());
        }
        if (result.changed()) {
            args.set(1, result.vertexText());
            args.set(5, result.fragmentText());
            if (ERYDON$TRANSFORM_LOGGED.compareAndSet(false, true)) {
                Erydon.LOGGER.info(
                        "[{}] Adapted CU gbuffers_terrain vertex and fragment stages in memory for CTM-aware POM.",
                        Erydon.MOD_ID);
            }
        } else if (("ANCHOR_MISMATCH_NO_CHANGE".equals(result.status())
                || "POSTCONDITION_FAILED_NO_CHANGE".equals(result.status())
                || "INCOMPLETE_TRANSFORM_NO_CHANGE".equals(result.status()))
                && ERYDON$FAILURE_LOGGED.compareAndSet(false, true)) {
            Erydon.LOGGER.warn("[{}] CU CTM-POM source adapter failed closed: {} {}",
                    Erydon.MOD_ID, result.status(), result.counts());
        }
        var polish = HighPolishShaderAdapter.adaptFragment(programName, args.get(5));
        if (polish.changed()) {
            args.set(5, polish.text());
            Erydon.LOGGER.info("[erydon] Adapted {} {} for high polish.", HighPolishShaderAdapter.profile(), programName);
        } else if ("UNSUPPORTED_SOURCE".equals(polish.status())) {
            Erydon.LOGGER.warn("[erydon] Opaque high polish left unsupported {} source unchanged.", programName);
        }
        if (((ErydonMetalProgramSetExtension) args.get(6)).erydon$isMetalEligible()) {
            var metal = MetallicShaderAdapter.adapt(programName, args.get(1), args.get(5), true);
            if (metal.changed()) {
                args.set(1, metal.vertex());
                args.set(5, metal.fragment());
            } else if ("UNSUPPORTED_SOURCE".equals(metal.status())) {
                // Preflight used these same sources. Never compile a partially adapted pipeline.
                throw new IllegalStateException("ERYDON metal source changed after preflight: " + programName);
            }
        }
        if ("gbuffers_terrain".equals(programName)) {
            args.set(1, HighPolishShaderAdapter.adaptSpiralPredicate(args.get(1)));
            args.set(5, HighPolishShaderAdapter.adaptSpiralPredicate(args.get(5)));
        }
        if ("shadow".equals(programName)) {
            var columns = HighPolishShaderAdapter.adaptColumnReflections(args.get(1));
            if (columns.changed()) {
                args.set(1, columns.text());
                Erydon.LOGGER.info("[erydon] Circular columns enabled in Complementary's approximate block reflections.");
            }
        }
    }
}
