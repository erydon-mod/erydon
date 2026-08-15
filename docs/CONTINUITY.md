# Continuity source and replacement

ERYDON currently embeds the unmodified Continuity `3.0.0+1.20.1` JAR to retain
the behaviour of the existing release build.

- Upstream release and source tag:
  <https://github.com/PepperCode1/Continuity/releases/tag/v3.0.0%2B1.20.1>
- Published version and source download:
  <https://modrinth.com/mod/continuity/version/qGTDcjHM>
- Licence: LGPL-3.0-only

The LGPL and incorporated GPL texts are in `LICENSES/` and are also packaged in
the built ERYDON JAR. ERYDON's MIT and CC BY-SA terms do not replace or restrict
Continuity's LGPL terms.

To build ERYDON against a compatible modified Continuity, build Continuity from
the tagged source (or your lawful modification), place the resulting JAR at
`libs/continuity-3.0.0+1.20.1.jar`, and run the normal ERYDON build. To inspect
or replace the nested component in an existing ERYDON JAR, use a ZIP-capable
archive tool and replace `META-INF/jars/continuity-3.0.0+1.20.1.jar` while
retaining the applicable notices and complying with both components' terms.

Users may exercise all rights granted by LGPL-3.0-only, including lawful
modification, replacement, debugging, and reverse engineering for debugging a
modified LGPL component.

