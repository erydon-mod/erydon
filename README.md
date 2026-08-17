# ERYDON

ERYDON is a Fabric mod for Minecraft 1.20.1 focused on detailed architectural
building. It provides decorative stone and marble families, connected textures,
custom slopes and structural pieces, lighting, and classical through modern
design details.

This repository contains the standard ERYDON mod source and the runtime assets
required to build its JAR. It does not contain old Git history, development
worlds, local runtime state, or the separately distributed ERYDON Collection
32x/64x pack ZIPs.

## Supported platform

- Minecraft `1.20.1`
- Fabric Loader `0.16.10` or newer compatible 1.20.1 release
- Fabric API `0.92.11+1.20.1` or newer compatible 1.20.1 release
- Java 17
- Family compatibility generation `2` (`compat2`)

The build embeds Continuity `3.0.0+1.20.1` for current runtime parity. Players
do not need to install a second copy solely for ERYDON. See
`docs/CONTINUITY.md` for source and replacement information.

## Installation

1. Install Minecraft 1.20.1 and Fabric Loader.
2. Install Fabric API.
3. Copy the ERYDON JAR into the instance's `mods` folder.
4. Optional rendering, recipe-viewer, Mod Menu, EMI, and Axiom integrations are
   enabled when their compatible mods are installed.

Official Collection resource packs are distributed separately so this source
repository remains a practical size. Use only releases published by ERYDON's
official project pages.

## Build prerequisites

- A 64-bit JDK 17
- Python 3.13 or newer
- Internet access for Gradle's declared build dependencies

On Windows PowerShell:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
.\gradlew.bat clean build
```

On Linux or macOS:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
./gradlew clean build
```

The release-shaped output is written to:

`build/libs/erydon-fabric-mc1.20.1-compat2-1.5.14.jar`

The standard `build` task runs compilation, tests, source audits, texture-alias
validation, restored-family fixture checks, and the final JAR audit. For the
additional read-only model safety suite, run:

```text
./gradlew auditErydonModelGeometrySafety
```

See `docs/VALIDATION.md` for the public and internal validation boundary.

## Project status and support

ERYDON remains on Fabric/Minecraft 1.20.1. Bugs and public source questions
belong in <https://github.com/erydon-mod/erydon/issues>. Security reports should
follow `SECURITY.md`.

## Licences

- ERYDON code: MIT — `LICENSE-CODE.md`
- ERYDON-owned assets: CC BY-SA 4.0 — `LICENSE-ASSETS.md`
- Third-party components: their original terms — `THIRD_PARTY_NOTICES.md`

The root `LICENSE` is the authoritative path-to-licence map.

