package com.oliver.erydon.mixin.client.compat.iris;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.pom.ComplementaryUnboundDev5SourceTransformer;
import com.oliver.erydon.client.pom.ErydonCuPomAdapterConfig;
import com.oliver.erydon.client.pom.ErydonIrisShaderPropertiesExtension;
import net.irisshaders.iris.gl.texture.TextureDefinition;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.properties.ShaderProperties", remap = false)
public abstract class ShaderPropertiesMixin implements ErydonIrisShaderPropertiesExtension {
    @Unique
    private boolean erydon$cuPomEligible;

    @ModifyVariable(
            method = "<init>(Ljava/lang/String;Lnet/irisshaders/iris/shaderpack/option/ShaderPackOptions;Ljava/lang/Iterable;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/shaderpack/preprocessor/PropertiesPreprocessor;preprocessSource(Ljava/lang/String;Lnet/irisshaders/iris/shaderpack/option/ShaderPackOptions;Ljava/lang/Iterable;)Ljava/lang/String;",
                    remap = false
            ),
            argsOnly = true,
            ordinal = 0,
            require = 1,
            remap = false
    )
    private String erydon$adaptRawShaderProperties(String contents) {
        ComplementaryUnboundDev5SourceTransformer.Result result =
                ComplementaryUnboundDev5SourceTransformer.adaptProperties(
                        contents,
                        ErydonCuPomAdapterConfig.configuredMode());
        erydon$cuPomEligible = result.eligible();
        if (result.changed()) {
            Erydon.LOGGER.info("[{}] Enabled the exact Complementary Unbound dev5 CTM-POM adapter in memory.",
                    Erydon.MOD_ID);
        }
        return result.text();
    }

    @Inject(
            method = "<init>(Ljava/lang/String;Lnet/irisshaders/iris/shaderpack/option/ShaderPackOptions;Ljava/lang/Iterable;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void erydon$registerCuPomSamplerDirectly(
            String contents,
            ShaderPackOptions options,
            Iterable<StringPair> environmentDefines,
            CallbackInfo ci
    ) {
        if (!erydon$cuPomEligible) {
            return;
        }
        ShaderProperties properties = (ShaderProperties) (Object) this;
        properties.getIrisCustomTextures()
                .put("erydonCtmPomLookup",
                        new TextureDefinition.PNGDefinition("erydon:ctm_pom_lookup"));
    }

    @Override
    public boolean erydon$isCuPomEligible() {
        return erydon$cuPomEligible;
    }
}
