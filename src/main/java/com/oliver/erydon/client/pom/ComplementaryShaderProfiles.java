package com.oliver.erydon.client.pom;

import java.util.Map;

/** Audited release fingerprints, followed by independent program-source preflights.
 * Unbound and Reimagined share properties in these releases; filenames are not evidence.
 */
public final class ComplementaryShaderProfiles {
    private static final Map<String, String> SUPPORTED = Map.of(
            "509707559658f5e833e800cac78905ad2d9ab5ddd9b4d0cd5d613bb99de98f6b", "r5.6.1",
            "253aa50ba3e61fed9a608f3746e2f1f6743cce1bab7221d24949c5ed97ebc89b", "r5.8.1",
            "35f1e5db99ba5815fdd15cdf3cf9bd4942c6fb57d531155064ab5148fce10835", "r5.9_dev2",
            "a4c4e2156ad5aeb66c0ad495a2721ea092952f2cdebfaaf89a20dfa3409ef2e8", "r5.9_dev5",
            "b29be050d6296bc5df769a66e351adbaaa9108eec801eb0442137a7a81266ab7", "r5.9.3 / r5.9.4_dev1",
            "d44f78b1bc01ec483fdbe70f812ee6752e57d4d203123950a1fbd398c57c73a2", "r5.10_dev2",
            "96a0cc7b19d08cde82665295bcc2078087963cb2a8e3dea36dc0415010da9615", "Reimagined r5.10_dev3");
    private static final String OLD_PROPERTIES =
            "4ab67e840f6daeb5d34260acea26f41fa64370c7da23a1efbb5eb8ca443e8f75";

    public static boolean supported(String properties) {
        return properties != null && SUPPORTED.containsKey(ComplementaryUnboundDev5SourceTransformer.sha256(properties));
    }

    public static String diagnostic(String properties) {
        if (properties == null) return "UNSUPPORTED_PROPERTIES: missing shaders.properties";
        String hash = ComplementaryUnboundDev5SourceTransformer.sha256(properties);
        String release = SUPPORTED.get(hash);
        if (release != null) return "AUDITED_COMPLEMENTARY " + release + "; program preflight required";
        if (OLD_PROPERTIES.equals(hash)) return "UNSUPPORTED_COMPLEMENTARY r5.5.1: incompatible reflection/metal source";
        return "UNSUPPORTED_PROPERTIES sha256=" + hash + "; native shader rendering retained";
    }

    private ComplementaryShaderProfiles() { }
}
