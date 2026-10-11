# Optional client frame recorder

This separate Fabric 1.20.1 companion records completed Minecraft frame intervals without administrator prompts. It is dormant until a local capture request is queued. It never moves the camera, changes settings/world data, takes focus, hides windows or closes Minecraft.

The hook is `MinecraftClient.render(boolean)` at return. Consecutive timestamps include buffer swaps, the frame limiter and intervening ticks/stalls. These are CPU wall-clock frame intervals, not GPU execution or monitor scanout times. VSync and the FPS cap are recorded as context; neither setting is changed or rejected.

## Build and install

Build only when a test artifact has been authorised:

```text
gradlew -Perydon.frameRecorder=true frameRecorderTest verifyFrameRecorderIsolation remapFrameRecorderJar
```

The installable output is `build/frame-recorder/erydon-frame-recorder-fabric-mc1.20.1-compat2-1.1.1.jar`. The adjacent `-dev.jar` is not an installable remapped artifact. Install the ordinary JAR in the selected closed client's `mods` folder, then start Minecraft normally. It has its own mod ID/version and can be removed independently. Its sources, resources and mixins are excluded from ordinary ERYDON builds, including when the opt-in tasks are present.

No service, scheduled task, driver, Java agent, launch argument or security change is required. Compilation/unit tests do not replace first-launch verification that the mixin loaded and a real game capture completed.

## Request protocol

The root is `<gameDir>/erydon-frame-recorder`. Place a UTF-8 JSON file in `requests/<id>.json` using an atomic publish/rename after writing it completely. Requests can be queued before the next launch. The optional `tools/frame_recorder.py` helper provides request/status/summary commands; use its `--help` for the current syntax.

```json
{"id":"overlay-baseline-01","warmupSeconds":5,"captureSeconds":60,"timeoutSeconds":300}
```

IDs are 1–48 ASCII letters/digits/underscores/hyphens, start with a letter/digit, and exclude Windows device names. The ID must match the filename. Unknown fields, output paths and commands are rejected. Bounds are warmup 2–60 seconds, capture 1–120 seconds, and timeout at least warmup + capture + 5 seconds and at most 600 seconds. Defaults are 5/60/300. Use a new ID for every run; existing results are never replaced.

## Optional GPU stage diagnostics

Version 1.1.1 also accepts `"gpuStages":true`, or the helper's `request --gpu-stages` option. Ordinary requests remain compatible and issue no OpenGL timing queries. Explicitly opted-in captures sample roughly one frame per second using bounded asynchronous timestamp queries on OpenGL 3.3 or its timer-query extension. Query results are read only after availability; there is no GPU finish, blocking wait, render-state change or rendering change.

Supported Iris 1.7.6/Sodium 0.5.13 hooks measure main terrain, shadows, depth-copy/deferred, composite/final, and output color-space conversion separately. Terrain inside the shadow span is excluded from the main terrain total. The separate `gpu-stages.json` reports samples, availability, errors and unresolved queries dropped at capture completion. Missing hooks or unavailable results must not be interpreted as zero cost.

These durations are elapsed time on the GPU command timeline, potentially including idle time while the CPU submits work. They are not GPU-busy telemetry or display scanout, and they do not cover every rendering operation. Instrumented frames may incur query overhead, so use them to locate expensive stages; use ordinary paired captures for final FPS comparisons. The existing focus, camera, scene and file guards still apply.

## Capture lifecycle

One request runs at a time. Output lives in `results/<id>/`: the original request, atomic `status.json`, runtime/configuration snapshots, file fingerprints and, only after successful validation, `frames.csv`. Status is `WAITING`, `CAPTURING`, `COMPLETE`, `INVALID` or `TIMED_OUT`. `CAPTURING` is published immediately before the first eligible sampling frame; its reason identifies this short armed interval. No status or heartbeat disk writes occur during timed sampling, so a capture's interval count updates when it finishes. `heartbeat.json` records the process ID and completed-frame hook count while idle/waiting; it intentionally stays unchanged during capture.

After initial world readiness, render-thread metadata is handed to the background worker for file hashing. Warmup starts only after hashing completes. The worker publishes the capture status before permitting the frame hook to start, so recorder IO never overlaps the timed sample. A newly accepted request has a total deadline, including startup, waiting and hashing; use 600 seconds when a slow client launch is expected.

## Acceptance guards and evidence

A capture needs a loaded world/player, window focus, no menu/overlay/pause, enabled world rendering, completed terrain rendering and an unchanged exact camera/entity/framebuffer throughout a continuous warmup. Once armed/capturing, any readiness/camera/world/resize interruption invalidates the entire run; it is not silently trimmed or resumed. Focus/menu/overlay transitions are also latched so a brief interruption between returned frames is rejected.

For Sodium `0.5.13+mc1.20.1`, the optional terrain guard also checks worker activity, pending build results/rebuild queues, graph updates and visible section count. Unrecognised Sodium versions/contracts fail closed. With Sodium absent, the vanilla completion guard applies. Natural time/weather are recorded at each end; the recorder does not freeze them, and paired runs still need comparable scenes/lighting.

Initial/final configuration includes loaded mod IDs/versions, Java/heap/GPU context, runtime options, enabled resource packs, VSync/FPS cap, world identity and framebuffer size. The IO worker fingerprints installed mod JARs, options, the known renderer configuration files, enabled file-based resource packs, and the selected shader pack/options. Runtime/configuration changes invalidate results. In-memory dynamic behaviour of arbitrary third-party mods cannot be exhaustively frozen or observed; the recorder reports the conditions it actually checks.

Every positive frame interval is retained in a preallocated buffer, including long stalls and a final frame that crosses the requested duration. Buffer overflow, non-increasing timestamps and a request deadline invalidate the run rather than dropping samples. A deadline failure can occur without another frame returning. `frames.csv` uses `frame_index,frame_ms`; consumers must require `COMPLETE`, contiguous indices, matching counts and positive finite intervals. Average FPS is `1000 / mean(frame_ms)`, not the mean of per-frame reciprocals.

The worker fails closed on metadata/IO errors without crashing or exiting the client. A missing/stale status, dead process, unfinished request or non-COMPLETE state is never usable FPS evidence. Recording results are local diagnostics and must not be committed to the repository.
