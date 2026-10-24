# Portorium branding

The polished stone plaques, metal inlays and Cinzel lettering form the shared
ERYDON family branding. Use the animated full logo in wide spaces and the animated
emblem in compact or square spaces wherever animation is supported. Static-only
slots, including standard Mod Menu icons and resource-pack selection icons, use
the static square emblem. The 32x and 64x resource packs retain separate resolution
lettering.

## Written names

Use **ERYDON**, **ERYDON Themelios**, **ERYDON Daedalon** and
**ERYDON Metrakron** for official mod names, listings and full titles.
Themelios, Daedalon and Metrakron may be shortened in descriptive prose.
Always write ERYDON in capitals. The **ERYDON Collection** is the family of mods
and resource packs. Pack names are **ERYDON 32x Resource Pack** and
**ERYDON 64x Resource Pack**.

The approved plaque artwork retains its engraved capital-letter typography.
Written names follow the convention above. Technical mod IDs, namespaces,
commands, URLs and artifact filenames retain their existing spelling and case.

Oliver owns the source photograph of real Portoro marble. GPT was used to
upscale that photograph; it was not generated as a new stone texture. Oliver
confirmed this provenance on 13 September 2026. The approved plaque geometry,
lettering and lighting were assembled and rendered in Blender with Codex
assistance. Exported images retain the approved appearance and texture crops.

Lettering uses Cinzel SemiBold by the Cinzel Project Authors (Natanael Gama),
under the SIL Open Font License 1.1: https://github.com/NDISCOVER/Cinzel.
These branding exports contain rendered lettering, not an embedded font file.
Existing repository asset licences and third-party notices continue to apply.

Preserve the approved artwork and aspect ratio. Use purpose-sized 8-bit exports
for each destination, keeping high-resolution masters outside runtime resources.
README banners belong under `docs/branding`, outside `src/main/resources`, and
use the approved animated full-logo GIF. GIFs are for documentation and web
surfaces, not Minecraft textures.

Creative-tab animation uses transparent 64-pixel square PNG frames and one
Minecraft tick per frame, without interpolation. Custom GUI logos and emblems
use filtered, purpose-sized 20-fps sheets drawn only while the relevant menu is
visible. Frame counts and loop lengths vary intentionally between products and
exports; keep the approved timing rather than forcing a common frame count.

## Branding validation

Use the approved animated full logo for wide README and custom GUI headers,
and the animated emblem for compact custom GUI slots wherever animation is
supported. Keep static square emblems in standard Fabric, Mod Menu and resource-
pack selection slots. Preserve aspect ratio and the approved playback timing.
Use purpose-sized 8-bit runtime exports; keep full-resolution artwork masters
outside runtime resource directories. Documentation GIFs stay in `docs/branding`.

Run `python -B tools/audit_branding.py` for the portable source audit. Its
`config/branding-assets.json` records approved hashes, dimensions, frame counts,
timing and consumers. Update that contract only when deliberately replacing an
approved export; never resize an entire animation atlas as if it were one frame.
The matching `tools/test_branding.py` checks rejection of damaged, mistimed,
unreferenced or oversized branding assets. Gradle source/resource checks run
these gates automatically; resource-pack CI runs the same source audit.
