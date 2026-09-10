# Experimental texture-alias index

Pack 01 adds an immutable prefix index to each texture-alias wrapper. It narrows
the aliases considered by `findResources`; opening files, physical-file priority,
resource-pack priority, namespaces and blob/hash validation are unchanged.
No streams or resource-manager results are cached. Closing/reopening a pack
creates a fresh wrapper and index.

The default is **off**. Enable before opening packs with the child Minecraft JVM
property `-Derydon.perf.alias_index=true`, or pass `--alias-index` to the measurement
runner's `prepare` command. The runner records and forwards the same flag value.
For a direct foundation Gradle run, use `-Perydon.perf.alias_index=true` with the
other required foundation arguments. Restart between feature-mode trials.

Both baseline and indexed variants run the complete texture-alias resource-manager
regression suite:

```text
gradlew test --tests '*TextureAlias*'
gradlew test check
```

The first pass checks empty/exact/directory/missing prefixes, encounter order,
namespace boundaries, physical and higher-pack overrides, metadata, missing or
corrupt blobs, unique-blob validation and repeated close/reopen. A separate helper
probe checks the current generated native-mod alias catalogue and estimates index
construction/retained-memory costs. Helper timings are not Minecraft loading or
FPS measurements, and do not establish a player-visible gain.

Keep the experiment disabled by default pending controlled loading/reload trials,
including the construction and memory cost. Use identical resource packs on both
sides. The uncertain 64x archive supplied for the earlier review was not used for
this pass, and no CTM properties or textures were changed.
