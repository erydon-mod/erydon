package com.oliver.erydon.recorder;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResultStoreTest {
    @TempDir Path directory;

    @Test void requestCannotSupplyPathsCommandsFractionalNumbersOrUnboundedTime() {
        assertThrows(Exception.class, () -> CaptureRequest.parse("{\"id\":\"../escape\"}", "../escape.json"));
        assertThrows(Exception.class, () -> CaptureRequest.parse("{\"id\":\"a\",\"command\":\"quit\"}", "a.json"));
        assertThrows(Exception.class, () -> CaptureRequest.parse("{\"id\":\"a\",\"captureSeconds\":1.5}", "a.json"));
        assertThrows(Exception.class, () -> CaptureRequest.parse("{\"id\":\"a\",\"captureSeconds\":121}", "a.json"));
        assertThrows(Exception.class, () -> CaptureRequest.parse("{\"id\":\"a\"}", "b.json"));
        assertFalse(CaptureRequest.safeId("CON"));
        assertEquals(new CaptureRequest("a", 5, 60, 300), CaptureRequest.parse("{\"id\":\"a\"}", "a.json"));
    }

    @Test void partialFilesAreIgnoredAndAcceptedRequestIsClaimedOnlyOnce() throws Exception {
        var store = new ResultStore(directory);
        Files.writeString(store.requests.resolve("partial.pending"), "{");
        Path request = store.requests.resolve("run01.json");
        Files.writeString(request, "{\"id\":\"run01\"}");
        assertEquals(java.util.List.of(request), store.pending());
        Path output = store.reserve(request);
        assertEquals("run01", store.readRequest(output).id());
        assertFalse(Files.exists(request));
        Files.writeString(request, "{\"id\":\"run01\"}");
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> store.reserve(request));
        assertEquals("{\"id\":\"run01\"}", Files.readString(output.resolve("request.json")));
        assertTrue(store.pending().isEmpty());
    }

    @Test void csvKeepsLongIntervalsAndAtomicStatusHasNoPendingRemainder() throws Exception {
        Path output = Files.createDirectory(directory.resolve("capture"));
        ResultStore.frames(output, new long[]{10_000_000, 2_345_678_901L});
        assertEquals("frame_index,frame_ms\n0,10.000000\n1,2345.678901\n", Files.readString(output.resolve("frames.csv")));
        ResultStore.atomicJson(output.resolve("status.json"), java.util.Map.of("state", "COMPLETE"));
        assertFalse(Files.exists(output.resolve("status.json.pending")));
    }

    @Test void staleAndUnsafeQueueEntriesCannotBlockLaterValidRequests() throws Exception {
        var store = new ResultStore(directory);
        for (int i = 0; i < 20; i++) {
            Files.writeString(store.requests.resolve("old" + i + ".json"), "{}");
            Files.createDirectory(store.results.resolve("old" + i));
        }
        Files.writeString(store.requests.resolve("CON.json"), "{}");
        Path valid = store.requests.resolve("valid.json");
        Files.writeString(valid, "{\"id\":\"valid\"}");
        assertEquals(java.util.List.of(valid), store.pending());
    }
}
