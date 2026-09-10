# Development measurement foundation

This opt-in source set measures completed-frame intervals. Ordinary builds do not
configure it and release JARs do not include its classes or mixin resources.
No performance improvement is claimed by this infrastructure.

Enable with `-Perydon.perf.foundation=true`. Offline checks:

```text
gradlew --no-daemon --max-workers=2 -Perydon.perf.foundation=true perfTest verifyPerfConfiguration
python -B -m unittest discover -s tools/tests -p test_perf_foundation.py
```

Use the supplied **00_Measurement_Guard_Repair_v2/tools** directory as the shared
offline authority. Its packaged-instance guard intentionally does not approve a
development classpath; the runner below uses runtime Fabric identities, exact
output origins, file hashes and actual child-JVM values for that separate case.

```text
python tools/perf_foundation.py --repair-tools REPAIR_TOOLS prepare --instance REPO/build/perf-instance --out OUTSIDE_REPO/A
```

Preparation fingerprints the actual dirty source before and after compilation,
rejects source changes during that interval, fingerprints runtime inputs and
writes an explicit launch description. It uses a fixed 2G initial/8G maximum heap
and at most two Gradle workers. `--max-heap 16G` explicitly selects a larger fixed
limit when measured loading requirements and available RAM justify it; the initial
8G trial exhausted its heap during model loading. Preparation does not launch Minecraft. Keep these
settings fixed across trials and verify available memory and power settings.

Append `--launch` for one client run. A focused game window is necessary; do not
run another game or GPU-heavy workload during a final trial. The runner refuses
to launch when it detects another Minecraft client. Runtime captures stop the
dedicated client when complete/invalid; they never stop other processes.

The only supported instance is `build/perf-instance`, marked
`ERYDON_PERF_DISPOSABLE`. It must contain a freshly created disposable world named
**Erydon Performance Disposable**. No real save should be copied or renamed for
this purpose. Freeze scene, time/weather, camera, pack order and shader preset.
Turn off VSync and use the unlimited FPS setting. The recorder waits for a loaded
world, focus, closed menus and completed terrain, then requires five stable
seconds and captures 60 seconds. It rejects focus/menu/camera/window/terrain
interruptions or overflow. It times out after five minutes without completion.

Source/build hashing and inventory writes happen outside the timed window. Raw
CSV is written only after capture ends. Snapshot/configuration changes fail the
capture. Missing reports and process failure also invalidate the trial. A frame
hook or loader failure must be investigated before another trial is attempted.

Prepare/run an independent B with the identical configuration, then compare:

```text
python tools/perf_foundation.py --repair-tools REPAIR_TOOLS compare OUTSIDE_REPO/A OUTSIDE_REPO/B --out OUTSIDE_REPO/aa-smoke.json
```

Indigo, Indium and Iris regenerate a timestamp comment in their properties files
on every launch. If these are the only differences, add
`--instance build/perf-instance`. The comparison reconstructs both original file
hashes by changing only that same-day timestamp, preserving every other byte.
It rejects any actual settings change, unknown file, missing file or unproved
hash. Original runtime reports remain untouched; the comparison includes the
proof. Within-capture checks still require exact raw file hashes.

Runtime validation on 2026-09-05 produced two complete 60-second captures with
no dropped intervals and verified child JVM flags and compiled ERYDON origins.
The frame values describe the small disposable scene, not an optimisation gain.
Those captures predate the added automatic pause self-test; their exact compiled
inputs and source checkpoint remain recorded in their original reports.

Use `prepare --pause-self-test` with the same instance, output and heap arguments
to prepare the automatic pause-menu check, and append `--launch` to execute it.
It hides its own window and temporarily disables pause-on-lost-focus in memory.
After stable warm-up and ten live intervals, it opens Minecraft's actual pause
menu and requires rejection. It restores the original pause option before exit.
This control test bypasses focus eligibility, is explicitly marked
`performance_measurement: false`, cannot write frames.csv, and cannot pass the
FPS comparison guard. Normal FPS captures still require focus. It is not a test
of operating-system focus events or keyboard input.

The hidden client still needs the same loading memory as a visible client. Run it
when enough memory is free; the launcher still refuses another Minecraft client.
Automated interruption/overflow unit tests pass. A broad visual check is not
required for this development-only foundation; reserve it for later changes to
production rendering or assets.

The A/A comparison checks measurement plumbing only. A live interruption exercise
remains a distinct requirement, and compilation/unit tests alone do not prove it.
