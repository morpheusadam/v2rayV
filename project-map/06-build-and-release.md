# 06 — Build and release

## Toolchain

Nothing is on PATH. Everything was installed under `.buildtools/` and must be pointed at.

```powershell
$env:JAVA_HOME    = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:ANDROID_HOME = "<repo>\..\.buildtools\android-sdk"
```

| Tool | Version |
|---|---|
| JDK | 21 |
| Android SDK | platform 37, build-tools 37.0.0 |
| NDK | 29 |
| Kotlin | 2.4.0 |

```powershell
cd V2rayNG
.\gradlew.bat assemblePlaystoreDebug   -PABI_FILTERS=arm64-v8a   # phone
.\gradlew.bat assemblePlaystoreDebug   -PABI_FILTERS=x86_64      # emulator
.\gradlew.bat assemblePlaystoreRelease -PABI_FILTERS=arm64-v8a   # release
.\gradlew.bat cleanTestPlaystoreDebugUnitTest testPlaystoreDebugUnitTest --tests "com.v2ray.ang.automode.*"
```

## Two things are not in the repository

1. **`libv2ray.aar`** — 56 MB, from the
   [AndroidLibXrayLite releases](https://github.com/2dust/AndroidLibXrayLite/releases) whose
   tag matches the pinned submodule. Goes in `V2rayNG/app/libs/`.
2. **The `hev-socks5-tunnel` native libraries.** Run `compile-hevtun.ps1`. On Windows it
   also materialises the submodule's git-symlink headers as real files, without which the
   compiler reads a path string as C.

## Signing

A release build needs `V2rayNG/signing.properties`, which is gitignored:

```properties
storeFile=<path>/.buildtools/automode-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it the release comes out **unsigned rather than debug-signed**, on purpose.

> **Never regenerate the keystore.** Play Protect's reputation is attached to that
> certificate, and a new key restarts it from zero. Verify after building:
>
> ```powershell
> apksigner verify --print-certs <apk>
> ```
>
> The certificate SHA-256 must stay `e7adadbf…348e9b`.

## Traps that have cost time

- **`Select-Object -First N` on a gradle pipeline kills the build.** It terminates the
  upstream pipeline early. Capture into a variable first, then filter.
- **`UtilsTest.test_isIpAddress` and `test_IsIpInCidr` fail on clean upstream too.**
  Verified by stashing. Do not chase them. Everything under `com.v2ray.ang.automode.*` and
  `notice.*` should be green — 102 tests at the time of writing.
- **The `hev-socks5-tunnel` submodule shows as dirty and should stay that way.** See above.
- **`LogUtil` reaches into MMKV**, which is not initialised in a plain JVM test. Anything
  that must stay unit-testable — parsers especially — must not log. Put the logging in the
  caller.
- **The emulator's software renderer ANRs.** Check `/data/anr/` before believing an ANR is
  yours; two were graphics stalls with no app frame in the stack.

## Cutting a release

1. Refresh the bundled snapshots from the companion repo — see
   [03](03-censorship.md). `BundledSnapshotTest` will tell you if they stopped parsing.
2. Bump `versionCode` **and `versionName`** in `app/build.gradle.kts`. The in-app update
   check compares the tag against `BuildConfig.VERSION_NAME`; leave the name alone and no
   installed app will ever see the release.
3. Run the tests.
4. Commit, then tag **annotated** and push both:

```bash
git tag -a v<version> -m "v2rayV <version>"
git push origin automode --follow-tags
```

5. Dispatch the build. **This is what produces the release** — CI builds every ABI, signs
   with the real keystore from `APP_KEYSTORE_BASE64`, drops the F-Droid flavour, and
   creates the release for the tag:

```bash
gh workflow run "Build APK" --repo morpheusadam/v2rayV --ref automode \
  -f release_tag=v<version> -f prerelease=false
```

6. Write the notes onto the release once CI has created it:

```bash
gh release edit v<version> --repo morpheusadam/v2rayV \
  --notes-file <notes.md> --latest --prerelease=false
```

> **Pushing a tag does not build anything.** No workflow in this repo has a `tags:` trigger.
> A push to `automode` builds APKs as workflow artifacts and stops there; only a manual
> dispatch with a non-empty `release_tag` publishes. This is the step that is easy to skip
> and leaves a tag with no release behind it.

> **A local `assemblePlaystoreRelease` is for checking the build, not for shipping.** It is
> still worth running — and verifying the certificate — but CI is what signs the artifacts
> that go out, from the same key by a different route. Uploading a locally built APK by
> hand is how the F-Droid flavour reached a release page in 2.4.0: `assembleRelease`
> produces both flavours, and globbing the output directory catches both.

> **Do not mark it pre-release.** `GITHUB_DOWNLOAD_URL` in the app is
> `releases/latest/download`, and `latest` skips pre-releases — the flag both hides the
> release from the repo's front page and breaks in-app updating.

The updater matches assets by **ABI substring**, so the filename must contain `arm64-v8a`.
Keep gradle's split names; a tidier `v2rayV-<version>.apk` breaks in-app updating outright.

> The release notes are read twice by machines. `announce.yml` sends the **first 600
> characters** to the Telegram channel, and `UpdateCheckerManager` shows the whole body in
> the in-app update dialog. Put the sentence that matters first, and write it to be read on
> a phone.

> `announce.yml` fires on `released`, not `published`. A pre-release or a draft never
> notifies the channel, and the APK it forwards must be under 50 MB — which is why only
> `arm64-v8a` goes, and the universal build stays on the release page.

## Play Protect

It will warn: *"Play Protect hasn't seen an app from this developer before."* That is a
judgement about how many installs the signing certificate has behind it, not about the APK.
It cannot be appealed and nothing in the file changes it; it fades only with installs on the
same key, which is another reason never to regenerate it. Users tap **More details → Install
anyway**.

The one lever left is dropping `REQUEST_INSTALL_PACKAGES` from the manifest, which exists
for in-app updating. It would reduce the risk of the harsher "harmful app" verdict but will
not remove this particular message, which is the unknown-developer path.
