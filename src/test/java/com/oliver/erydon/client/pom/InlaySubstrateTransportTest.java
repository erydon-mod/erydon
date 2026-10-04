package com.oliver.erydon.client.pom;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class InlaySubstrateTransportTest {
    @AfterEach void reset() {
        InlaySubstrateTransport.clearRecords();
        InlaySubstrateTransport.restoreRecord(-1);
        InlaySubstrateTransport.setSourceSupported(false);
    }

    @Test void recordOrderingExactlyMatchesTheExistingFamilyPhaseLayout() {
        var first = family("a");
        var second = family("b");
        InlaySubstrateTransport.installRecords(List.of(first, second));
        for (int phase = 0; phase < 36; phase++) {
            assertEquals(phase, InlaySubstrateTransport.recordForSprite(first.phases().get(phase)));
            assertEquals(36 + phase, InlaySubstrateTransport.recordForSprite(second.phases().get(phase)));
        }
        assertEquals(-1, InlaySubstrateTransport.recordForSprite(new Identifier("minecraft", "missingno")));
        InlaySubstrateTransport.clearRecords();
        assertEquals(-1, InlaySubstrateTransport.recordForSprite(first.phases().get(0)));
    }

    @Test void signedShortPayloadCannotOverlapNativeBlockOrFluidTypes() {
        for (int record = 0; record <= InlaySubstrateTransport.MAX_RECORD; record++) {
            short encoded = InlaySubstrateTransport.encodedRenderType(record);
            assertTrue(encoded <= -2);
            assertEquals(record, -encoded - 2);
        }
        assertThrows(IllegalArgumentException.class, () -> InlaySubstrateTransport.encodedRenderType(-1));
        assertThrows(IllegalArgumentException.class, () -> InlaySubstrateTransport.encodedRenderType(InlaySubstrateTransport.MAX_RECORD + 1));
    }

    @Test void scopedPayloadRestoresNestedValuesAndDoesNotEscapeItsWorkerThread() {
        assertEquals(-1, InlaySubstrateTransport.pushRecord(17));
        assertEquals(17, InlaySubstrateTransport.pushRecord(43));
        assertEquals(-1, CompletableFuture.supplyAsync(() -> InlaySubstrateTransport.pushRecord(-1)).join());
        InlaySubstrateTransport.restoreRecord(17);
        assertEquals(17, InlaySubstrateTransport.pushRecord(-1));
        assertEquals(-1, InlaySubstrateTransport.pushRecord(-1));
        assertEquals(-1, InlaySubstrateTransport.currentRecord(), "Shader path has not passed preflight");
    }

    @Test void ribbonFlagRetainsEveryEncodablePhaseWithoutOverlappingOrdinaryQuads() {
        for (int record = 0; record < InlaySubstrateTransport.MAX_RECORD; record++) {
            short encoded = InlaySubstrateTransport.encodedRenderType(record, true);
            int payload = -encoded - 2;
            assertTrue(encoded < InlaySubstrateTransport.encodedRenderType(InlaySubstrateTransport.MAX_RECORD));
            assertEquals(InlaySubstrateTransport.RIBBON_FLAG, payload & InlaySubstrateTransport.RIBBON_FLAG);
            assertEquals(record, payload & (InlaySubstrateTransport.RIBBON_FLAG - 1));
            assertEquals(InlaySubstrateTransport.encodedRenderType(record),
                    InlaySubstrateTransport.encodedRenderType(record, false));
        }
        assertThrows(IllegalArgumentException.class, () -> InlaySubstrateTransport.encodedRenderType(-1, true));
        assertThrows(IllegalArgumentException.class, () -> InlaySubstrateTransport.encodedRenderType(InlaySubstrateTransport.MAX_RECORD, true));
    }

    @Test void onlyVerifiedRendererVersionsAreEligible() {
        assertTrue(InlaySubstrateTransport.supportedVersions("1.7.6+mc1.20.1", "0.5.13+mc1.20.1", "1.0.36+mc1.20.1"));
        assertFalse(InlaySubstrateTransport.supportedVersions("1.8.0+mc1.20.1", "0.5.13+mc1.20.1", "1.0.36+mc1.20.1"));
        assertFalse(InlaySubstrateTransport.supportedVersions("1.7.6+mc1.20.1", "0.6.0+mc1.20.1", "1.0.36+mc1.20.1"));
        assertFalse(InlaySubstrateTransport.supportedVersions("1.7.6+mc1.20.1", "0.5.13+mc1.20.1", ""));
    }

    @Test void realRendererBytecodeMustMatchBeforeAnyNativeMemoryCanBeChanged() throws Exception {
        assertTrue(InlaySubstrateVertexFormat.supported());
        var encoder = InlaySubstrateVertexFormat.read(InlaySubstrateVertexFormat.ENCODER);
        var write = encoder.methods.stream().filter(m -> m.name.equals("write")).findFirst().orElseThrow();
        for (var instruction : write.instructions) {
            if (instruction instanceof LdcInsnNode value && Long.valueOf(34).equals(value.cst)) value.cst = 36L;
        }
        assertFalse(InlaySubstrateVertexFormat.matchesEncoder(encoder), "Changed field offset must disable transport");
        var format = InlaySubstrateVertexFormat.read("net/irisshaders/iris/compat/sodium/impl/vertex_format/terrain_xhfp/XHFPModelVertexType");
        for (var method : format.methods) for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.name.equals("SHORT")) field.name = "UNSIGNED_SHORT";
        }
        assertFalse(InlaySubstrateVertexFormat.signedRenderType(format), "Unsigned attributes cannot carry negative substrate IDs");
    }

    private static ErydonCuPomFamilyDiscovery.Family family(String name) {
        return new ErydonCuPomFamilyDiscovery.Family(name, IntStream.range(0, 36)
                .mapToObj(i -> new Identifier("minecraft", "optifine/ctm/" + name + "/" + i)).toList());
    }
}
