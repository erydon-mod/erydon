# ERYDON public candidate report

## Status

**READY FOR FINAL REVIEW**

The folder is a clean, history-free public-repository candidate. It has not
been initialised as Git, committed, uploaded, pushed, or connected to a remote.

## Candidate inventory

- Files: `90,537`
- Total size: `78,371,888` bytes
- Largest individual file: 1,394,543 bytes
- Files over 2 MB: 0
- Git object database: absent
- Build output and downloaded caches: absent after verification

### Included top-level paths

- `.gitattributes`
- `.github/`
- `.gitignore`
- `ASSET_PROVENANCE.md`
- `CONTRIBUTING.md`
- `LICENSE`
- `LICENSE-ASSETS.md`
- `LICENSE-CODE.md`
- `LICENSES/`
- `PUBLIC_ALLOWLIST.md`
- `PUBLIC_CANDIDATE_REPORT.md`
- `README.md`
- `SECURITY.md`
- `THIRD_PARTY_NOTICES.md`
- `build.gradle`
- `config/`
- `docs/`
- `gradle/`
- `gradle.properties`
- `gradlew`
- `gradlew.bat`
- `libs/`
- `requirements.txt`
- `settings.gradle`
- `src/`
- `tools/`

### Excluded top-level paths and categories

- `.git/`, all refs, objects, logs, tags, and old history
- `.idea/`, `*.iml`, `.aiassistant/`, `.gradle/`, `build/`, and `out/`
- `run/`, `run-dev/`, `runclient_errors/`, and `logs/`
- Resource-pack ZIPs, disabled mods, worlds, player data, screenshots, shader
  packs, profiling output, and local runtime configuration
- `mezz/`, `node_modules/`, `__pycache__/`, downloaded caches, and executables
- The `tools/main 050326/` snapshot, old CTM backups, PresentMon, FabricSkyBoxes,
  internal reports, and temporary path-derived files
- The three former inspiration screenshots with unresolved third-party visual
  provenance

The only JARs included in the source candidate are the Gradle wrapper and the
reviewed Continuity runtime component. No ZIP or executable is included.

## Build and validation results

Validation environment:

- Java: Temurin 17
- Python: 3.14.2
- Pillow: 12.1.1, pinned in `requirements.txt`
- Gradle wrapper: 8.7

Final command:

```text
gradlew clean build auditErydonModelGeometrySafety
```

Result: **BUILD SUCCESSFUL**, 35 of 35 tasks executed in 7 minutes 33 seconds.

Key results:

- Java compilation and tests passed. Eight existing deprecated-API warnings
  remain; there were no compilation errors.
- All 1,775 ID-migration rows passed: 1,586 permanent aliases and 189 direct
  renames, with zero stale resources, keys, or references.
- Texture inventory contained 25,673 logical PNG records. The build validated
  13,204 virtual aliases and 111 canonical blobs.
- Source and JAR CTM audits each checked 1,217 CTM property files and 45,924
  references with zero missing paths and zero repair candidates.
- Restored-family validation checked 10 samples; all 6 legacy-fixture
  comparisons matched and 4 current differences were documented.
- All 111 model-tool regression tests passed. One Windows symlink test was
  skipped because the verification process did not have symlink privilege.
- Model UV audit: 56,113 files, 90,852 faces, zero parse failures, and zero
  validation failures. It reports 88 existing arithmetic candidates, 584
  boundary-blocked faces, and 4 span-blocked faces for future model review.
- Z-fighting audit: 49,189 files, 1,644 report findings, and zero automatic
  candidates. No model was changed during candidate preparation.
- Raw authoring-model UV audit: 43 files, 4,442 faces, 47 existing out-of-range
  faces, zero rotated out-of-range faces, and zero unresolved findings.

The build generated reports and a JAR for inspection. The generated `build/`,
`.gradle/`, `logs/`, and disposable verification-cache folders were then
removed so they cannot enter a first commit.

## Built JAR inspection

Inspected artifact:

`erydon-fabric-mc1.20.1-compat2-1.5.14.jar`

- Size: 45,314,620 bytes
- SHA-256: `fa7a9bf4f772d65848aa6bf9fab9122969d16b2e12b612f40c1db7cb1a04d58d`
- Entries: 78,260
- Embedded version: 1.5.14
- Family compatibility generation: 2
- Fabric licence metadata: `MIT`, `CC-BY-SA-4.0`
- Required ERYDON code, assets, licence texts, and notices: present
- Nested Continuity: present and byte-identical to the reviewed source JAR
- Source `.properties` set: packaged byte-for-byte
- CTM files with UTF-8 BOM: 0
- Test fixtures, tools, screenshots, local paths, mail links, player data, and
  excluded development paths: absent

The inspected JAR was removed with the generated build output after recording
these results.

## Source parity and intentional runtime differences

The candidate/source comparison found 90,441 imported files byte-identical to
the current live source and zero unexpected mismatches.

Core gameplay, registries, models, textures, CTM, custom baked-model code,
authoring models, data, tests, and Continuity embedding remain sourced from the
authoritative current project. The intentional public-candidate differences
are:

- Private mail links were replaced with the planned public GitHub issue and
  community URLs.
- Fabric metadata now declares MIT plus CC BY-SA 4.0 and points to the planned
  public source/issues location.
- Three inspiration screenshots were replaced in the UI by existing
  ERYDON-owned Chalstrom, Striatus, and Glacium material swatches.
- Two semantically unchanged Gothic tag JSON files were normalized to the
  generators' canonical UTF-8/LF output so generator-idempotence tests pass.
- The historical geometry audit now uses six small legacy blockstate fixtures
  instead of a 40,112-file development snapshot.
- Spiral-stair CTM validation always requires bundled core coverage and also
  validates optional Collection roots whenever those separate roots exist.

No in-game launch or visual comparison was performed. Final review should
confirm the material-swatch gallery and public links in game; automated source,
geometry, CTM, packaging, and JAR checks all passed.

## Licence and attribution status

- ERYDON code: complete MIT text in `LICENSE-CODE.md`.
- ERYDON-owned assets: CC BY-SA 4.0 scope and attribution in
  `LICENSE-ASSETS.md`, with complete legal code in `LICENSES/`.
- Continuity 3.0.0+1.20.1: exact version, source tag, source-access and
  replacement instructions, LGPL-3.0-only text, incorporated GPL text, and an
  explicit non-restriction statement are present in the repository and JAR.
- Cinzel 2.000: exact upstream OFL-1.1 text and copyright statements derived
  from the official source and font metadata are present in the repository and
  JAR.
- Gradle wrapper 8.7: upstream licence and bundled-component notices are
  included.
- Unnecessary JEI, PresentMon, and FabricSkyBoxes copies are absent.

## Remaining final-review decisions

1. Review the code and asset licence split and obtain legal advice if desired.
2. Confirm the planned repository owner/name and public URLs before the first
   release.
3. Enable GitHub private vulnerability reporting after repository creation.
4. Run the supplied GitHub Actions workflow after the first push; it cannot run
   before a Git repository exists.
5. Perform an in-game smoke/visual check, especially the public material-swatch
   gallery. This is the only runtime verification not performed here.
6. Decide separately where the public Collection 32x/64x downloadable packs
   will be hosted; their ZIPs are intentionally not part of this repository.

The report-only UV and z-fighting findings above predate this candidate and are
not public-repository contamination. They remain visible for future model work.

## Suitability for publication

Subject to the final human/legal and in-game checks above, this candidate is
suitable for `git init` and publication as `erydon-mod/erydon`.

Do not import or attach the existing private repository's Git history. Start a
new history in this folder only after final review.

## Exact candidate-only changes

- Copied only the documented current source/build allowlist.
- Added the public licence map, complete licence texts, third-party notices,
  asset provenance, README, contribution policy, security policy, requirements,
  allowlist documentation, validation documentation, and CI workflow.
- Removed machine-specific Java-home configuration from the candidate.
- Updated candidate-only licence/contact metadata and the two public UI links.
- Substituted three owned material swatches for three excluded screenshots.
- Replaced the historical snapshot dependency with six explicit fixtures.
- Added only build-referenced validation tools and one transitive helper.
- Made the external Collection-pack assumption explicit in the spiral CTM test.
- Normalized two semantically unchanged generated tag files.
- Built, audited, inspected, and then removed all generated outputs and caches.

The existing source repository was not modified, initialised, committed,
uploaded, or pushed by this work.
