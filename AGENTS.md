# ERYDON repository instructions

## Repository

- Canonical remote: `https://github.com/erydon-mod/erydon`
- Default branch: `main`
- This repository is the authoritative ERYDON source.
- Derive exact build and validation commands from the current README and CI workflow rather than guessing.
- Use the authenticated `erydon-mod` GitHub identity and a GitHub-provided noreply commit address.

## Test JARs

- Do not create a test JAR unless the user explicitly asks for one.
- Routine validation and testing must stop at compilation, tests, and non-JAR audits. Do not run packaging tasks such as `jar`, `remapJar`, `build`, or `auditErydonJar` merely to prepare a test artifact.
- A specifically requested release, publication, or other named deliverable may still be built according to that request.

## Meaning of "commit"

When the user explicitly says `commit` or `commit these changes` after reviewing a completed task, Codex is authorised to:

1. Confirm the repository, remote, branch, and GitHub account.
2. Fetch the remote and check for divergence.
3. Inspect the complete working-tree diff.
4. Run relevant validation.
5. Stage only files belonging to the reviewed task, using explicit paths.
6. Review the staged diff for secrets, private files, generated noise, and unrelated changes.
7. Create a focused commit with a clear message.
8. Push normally to the intended branch.
9. Verify the local and remote commit hashes match.
10. Monitor GitHub Actions and report the result.

The instruction `commit` means commit and push unless the user explicitly says `commit locally only`.

It does not authorise force-pushing, rewriting history, deleting branches or tags, deleting the repository, merging pull requests, publishing releases, changing repository visibility, or modifying GitHub security or settings. Those actions require separate explicit approval.

Stop before committing or pushing if:

- Tests fail.
- The remote unexpectedly diverged.
- Authentication or repository identity is wrong.
- Sensitive or excluded material is found.
- Unrelated changes cannot be safely separated.

## Repository hygiene

Codex is responsible for routine Git and GitHub maintenance for this repository:

- Keep commits focused.
- Never discard unrelated user changes.
- Keep `.gitignore`, `.gitattributes`, CI, licences, and third-party notices accurate.
- Exclude build output, caches, IDE files, worlds, logs, temporary archives, and local configuration.
- Preserve intentional tracked runtime resources and generated Minecraft assets.
- Never add local absolute paths, personal email addresses, credentials, private locations, or other machine-specific information to public files.
- Diagnose failed GitHub Actions, but require a new `commit` instruction before pushing a corrective change.
- Never force-push.
- Help maintain issues, pull requests, dependencies, and releases when requested.

## Related projects

Daedalon, Themelios, and the ERYDON resource packs are intended to receive separate public repositories only after individual clean-export reviews. Do not create or publish them automatically; each requires its own licensing review, README, ignore rules, CI, and explicit publication approval. Never import private development history.
