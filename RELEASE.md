# Release Process

Each release needs **three pieces of release-note content** written before the tag is pushed. Missing any of them is why previous releases shipped with bare "Full Changelog" links or empty F-Droid changelogs.

| Surface | Location | Limit |
|---|---|---|
| F-Droid | `fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt` | ~500 chars |
| GitHub release body | Written below via `gh release edit` (or the UI) at tag time | no limit |
| Commit message body | Second line + body of the bump commit | keep short |

The arm64 versionCode is `versionCode × 10 + 1` (see `app/build.gradle.kts`: `output.versionCodeOverride = (defaultConfig.versionCode ?: 0) * 10 + abiCode`). For versionCode 246 → `2461.txt`.

## 0. Upstream any generic termlib fixes

Before tagging, look over the termlib commits since the last Haven release. Anything generic (no Haven-module dependency, no Haven-specific config, no references to `sh.haven.*`) belongs upstream in `connectbot/termlib` — carrying it on our fork long-term is rebase hazard (see `termlib/REBASE.md`).

```bash
cd /home/ian/Code/Haven/termlib
# Commits since the last upstream-synced point
git log upstream/main.. --oneline
```

For each candidate, decide:

- **Upstream-worthy** → open a PR against `connectbot/termlib` **before** tagging the Haven release. Even if it's not merged by release time, open it now so the conversation starts. Paste the PR URL into the Haven release notes so users can track it. Examples that should be upstreamed: IME robustness fixes, scroll/URL-detector fixes, terminal-core API additions (like `getSnapshotLineTexts`), the `replaceText` / `updateSelection` / internal-DEL fixes shipped in v5.21.0 (#99).
- **Haven-only, correctly** → the allowed list is in `termlib/REBASE.md` (`rawKeyboardMode`, `allowStandardKeyboard`, `composingText: StateFlow<String>`). Anything else on that list is either still awaiting upstream review or needs to be reconsidered.
- **Blocked on upstream review** → note the upstream PR number in the Haven release notes so users know the patch is in-flight, not divergent.

Don't block the Haven release on upstream acceptance — open the PR, link it, and ship. The rebase checklist (`termlib/REBASE.md`) picks up accepted PRs on the next monthly rebase and drops them from the fork.

## 1. Bump version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = <increment>
versionName = "<x.y.z>"
```

Also update the static release badge in `README.md` to match — the
`shields.io/badge/release-v<x.y.z>-blue` URL hard-codes the version
because the dynamic shields lookup is unreliable behind GitHub's
camo image proxy.

## 2. Write the changelog — BOTH files

A release needs release notes in **two** places. Missing either is a broken release:

1. **F-Droid** — `fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt`, the short note the F-Droid client shows. One summary line + a paragraph or two, under ~500 bytes. Miss it and F-Droid publishes the *previous* version's text.

2. **GitHub release body** — a `## v<x.y.z>` section at the top of the top-level `CHANGELOG.md` (newest first; the heading may carry a ` — title` suffix). The `publish` job runs `scripts/check-changelog.sh extract v<x.y.z>` and uses that section verbatim as the GitHub Release body (so there's no manual `gh release edit --notes` step). **Miss it and the `publish` job FAILS — *after* the full ~40-minute build — with `✖ No CHANGELOG.md section`, and no release is created.** House style: emoji + bold headline + prose, `(#NNN, thanks <user>)`.

★ **Before tagging**, run `bash scripts/check-changelog.sh check` (asserts the current `versionName` has a `CHANGELOG.md` section) and `bash scripts/check-changelog.sh extract v<x.y.z>` (prints it) — both must pass. If `publish` fails post-build for a missing section, add it, commit, then **move the tag** (`git tag -d v<x.y.z>; git push origin :refs/tags/v<x.y.z>; git tag v<x.y.z>; git push origin v<x.y.z>`) to re-trigger — re-running just the failed `publish` job re-uses the old tagged SHA and fails again.

## 3. Commit, tag, push

```bash
git add app/build.gradle.kts CHANGELOG.md fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt <other changed files>
git commit -m "Bump to v<x.y.z>"
git tag v<x.y.z>
git push origin main v<x.y.z>
```

The `v*` tag triggers the **Release** workflow (`.github/workflows/release.yml`),
a gated pipeline hardened under #211. It runs four jobs in sequence:

1. **`verify-ci`** — reads the combined commit status for the tagged SHA. If
   `main` CI already went green for this exact commit, the test job is skipped
   (same commit ⇒ same result); otherwise the pipeline runs the tests itself.
2. **`test`** — `./gradlew testReleaseUnitTest`. The build is gated on this, so a
   red test stops the release here. (This is why a broken `main` silently blocks
   publishing — only ever tag a green commit.)
3. **`build`** — signed APK **and** AAB for both ABIs (arm64 + x64); keystore +
   passwords come from repo secrets.
4. **`publish`** — ⏸ **reviewer sign-off gate (#211).** This job runs under the
   protected **`signing` GitHub Environment**, which requires an explicit
   approval from a designated reviewer (currently **@GlassOnTin**) and is
   restricted to `v*` tags / `main`. **The run pauses here until the reviewer
   approves** — so no single account can ship with the signing identity
   unilaterally. On approval it writes a build-provenance attestation, generates
   `SHA256SUMS`, and creates the GitHub Release with the signed APK/AAB attached
   (release notes are auto-generated; step 4 below adds human context).

### Approve the publish (the sign-off)

Until the reviewer approves, the run sits at `publish` and **the release does not
exist yet**. Approve it one of two ways:

- **GitHub UI (simplest):** *Actions → Release → (this run)* shows a
  **"Review deployments"** prompt → tick **signing** → **Approve and deploy**.
  Rejecting cancels the publish without shipping anything.
- **CLI:**
  ```bash
  RUN=$(gh run list --workflow=Release --limit 1 --json databaseId --jq '.[0].databaseId')
  ENV=$(gh api repos/GlassHaven/Haven/actions/runs/$RUN/pending_deployments --jq '.[0].environment.id')
  gh api repos/GlassHaven/Haven/actions/runs/$RUN/pending_deployments \
    -F "environment_ids[]=$ENV" -f state=approved -f comment="release v<x.y.z>"
  ```

The same `signing` Environment also gates `extract-secrets.yml`, so exporting the
keystore bundle needs the identical reviewer approval. Manage the reviewer list
under *Settings → Environments → signing*.

## 4. Fill in the GitHub release body

Once the release exists, edit its body so users see what changed instead of just a compare link. The fastlane changelog is a solid seed — copy it and expand with any context worth highlighting.

```bash
gh release edit v<x.y.z> --repo GlassHaven/Haven --notes-file fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt
```

Or open the release on GitHub and paste the notes into the body field.

## 5. F-Droid

F-Droid auto-detects new tags via `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`. The bot opens an update MR against `fdroid/fdroiddata`; linsui merges after the build succeeds. The fastlane changelog you wrote in step 2 is the text the F-Droid client displays.

### Tool-version alignment

F-Droid's buildserver is the authoritative build environment — failing there blocks the public release. The recipe at [fdroiddata/metadata/sh.haven.app.yml](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/sh.haven.app.yml) pins specific tool versions, and our CI mirrors them in `.github/actions/setup-toolchain/action.yml`. When you bump a pin in one place, bump it in the other — otherwise CI passes on a version F-Droid doesn't have and vice-versa.

> **Cross-compiler apt deps (`sudo:` block ↔ setup-toolchain):** the wayvnc-shim (`buildWayvncShim`) cross-builds a glibc `.so` for **every** ABI on any build, so the buildserver needs each target's cross-gcc + multiarch libc headers. x86_64 is native on the buildserver (no package). The recipe's `sudo:` install line must carry:
> `gcc-aarch64-linux-gnu libc6-dev-arm64-cross gcc-arm-linux-gnueabihf libc6-dev-armhf-cross`
> The **armhf** pair (`gcc-arm-linux-gnueabihf libc6-dev-armhf-cross`) is **new in v5.68.1** (the armv7 wayvnc shim, #327) — every existing recipe entry only has the arm64 pair, so the v5.68.x build entry must add armhf or the whole build fails at `buildWayvncShim`.

The buildserver no longer pre-installs NDKs in its Docker image — `fdroidserver.common.auto_install_ndk` fetches whatever the build entry's `ndk:` field requests via `sdkmanager` on demand. So the NDK pin lives in our gradle modules; the F-Droid manifest's `ndk:` line just tells the buildserver to install the same one.

| Tool | F-Droid pin | Our CI |
|---|---|---|
| NDK | `r29` (`29.0.14206865`) — declared per-module in `core/local`, `termlib/lib` | `sdkmanager "ndk;29.0.14206865"` in the composite action |
| Rust | `rustup default 1.85.0` (overridden by the toml below for the rdp build) | `rustup default 1.89.0`, and `rdp-kotlin/rust/rust-toolchain.toml` `channel = "1.89.0"` (not `stable`). The crate-scoped toml governs the only Rust build, so F-Droid's `1.85.0` default also compiles rdp at 1.89.0. **Floor: 1.87** (ironrdp-graphics 0.7.0 uses `integer_sign_cast`). Align F-Droid's default to 1.89.0 in a follow-up. |
| `cargo-ndk` | `3.5.4` | `3.5.4` |
| CMake | `3.31.6` | `3.31.6` |
| `gomobile` / `gobind` | `@latest` | **pinned** `@v0.0.0-20251021151156-188f512ec823` (matches `rclone-android/go/go.sum`); bump in lockstep with the go.sum entry + the `gomobile-cache` key |
| Go | `1.26` (implicit — matches our `actions/setup-go`) | `1.26` |

> Supply-chain hardening (#211) pinned our CI's `gomobile`/`gobind` to the
> `go.sum` pseudo-version instead of `@latest`. The F-Droid recipe still uses
> `@latest`; a follow-up should pin it there too so both build the binding
> generator from the same commit.

Things Haven's CI does **not** exercise but F-Droid does:

- `build-proot/build.sh`, `build-ffmpeg/build.sh`, `wayland-android/build_liblabwc_android.sh` — F-Droid has these in its `scandelete` list (it deletes the committed `.so`s and rebuilds from source). Our CI uses the committed pre-built binaries. A regression in any of those scripts won't show up until the F-Droid bot MR's build step fails on GitLab. If you touch those scripts or their deps, smoke-test locally before tagging:
  ```bash
  rm -rf core/ffmpeg/src/main/jniLibs core/wayland/src/main/jniLibs core/local/src/main/jniLibs
  ABI=arm64-v8a bash build-ffmpeg/build.sh
  bash build-proot/build.sh
  pushd wayland-android && ABI=arm64-v8a ./build_liblabwc_android.sh && popd
  ```

### If the F-Droid build fails

Watch [fdroid/fdroiddata merge requests tagged with our app](https://gitlab.com/fdroid/fdroiddata/-/merge_requests?scope=all&search=sh.haven.app). The bot MR shows the build log; common causes are tool-version skew vs what's above, new apt deps the recipe's `sudo:` block doesn't install, or submodule commits the buildserver can't reach. When fixed, comment `@fdroidbot rebuild` on the MR.

## Dependency verification

`gradle/verification-metadata.xml` pins a SHA-256 for every external Gradle/Maven artifact (sha256 only — `verify-signatures=false`). Any Gradle invocation now fails if a resolved artifact's checksum isn't listed, so a poisoned mirror or swapped artifact is caught.

**This means every dependency change must regenerate the file**, or the build fails with `Dependency verification failed ... is not in verification-metadata.xml`. After bumping any Gradle dependency / plugin (incl. merging a Dependabot `gradle` PR):

```bash
./gradlew --write-verification-metadata sha256 \
  :app:assembleArm64Debug testDebugUnitTest :app:testArm64DebugUnitTest lintDebug \
  --console=plain
git diff gradle/verification-metadata.xml   # review the added checksums on a clean checkout
```

The task set above is a superset of what CI and the release build resolve (no release-only or ABI-specific deps). It captures composite-build deps and the JitPack Tesseract4Android AAR. Native deps (Go modules, Rust crates) are out of scope — they're integrity-pinned by `go.sum` / `Cargo.lock`. `androidTest`/connected-test deps are not covered (CI doesn't run them); add them to the task list if that changes.

## 6. Verify

- [ ] CI workflow is green on the tagged commit (the `publish` sign-off gate
      assumes it — see `verify-ci`)
- [ ] `publish` deployment approved by a `signing`-environment reviewer (the
      release won't exist until then — see "Approve the publish" above)
- [ ] GitHub release page has the APK + AAB, `SHA256SUMS`, and a non-empty body
- [ ] `fastlane/.../changelogs/<code>.txt` exists and is committed

## Signing

The release keystore `haven-release.jks` is in the repo root. Passwords are stored in GitHub secrets:

- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`
- `KEY_ALIAS`

The signing identity is **reviewer-gated** in CI (#211): the `publish` job and
the `extract-secrets.yml` bundle export both run under the protected `signing`
GitHub Environment, so neither ships/exports without an approval from a required
reviewer. See "Approve the publish (the sign-off)" under step 3.

Local release builds require these as environment variables:

```bash
export KEYSTORE_PASSWORD=<password>
export KEY_PASSWORD=<password>
./gradlew :app:bundleRelease
```

## F-Droid details

- F-Droid builds from source using the tagged commit.
- `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` means F-Droid auto-detects new tags.
- Initial inclusion MR: `fdroid/fdroiddata!33920` (merged).
