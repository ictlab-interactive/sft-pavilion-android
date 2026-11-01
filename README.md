# SFT Pavilion Android — recovered updater build

This project adds a native updater to the existing SFT Pavilion APK while retaining
the original website wrapper, navigation, notification code, and timetable reminders.
It was recovered from the published v1.2.6 APK because the original Android project
was unavailable. The original app remains a hash-pinned compiled baseline; this is
not a reconstruction of its original Java source. New updater code is in `src/`.

## What students see

- A fourth **Updates** button in the bottom navigation.
- An automatic update check after opening the app; successful checks are spaced
  six hours apart, and offline failures do not block the website.
- **Update now / Later**, release notes, download size, and download progress.
- A verified APK download followed by Android's installation confirmation.
- Android 8+ may first require **Allow from this source** for SFT Pavilion.

The installed version is read from Android's package manager. The update channel is:

`https://github.com/ictlab-interactive/sft-pavilion-android/releases/latest/download/update.json`

The updater uses version codes, not alphabetical tag comparisons. The APK URL is
constructed for this exact repository and version; metadata cannot redirect the app
to an arbitrary publisher. Downloads are bounded, checked for size and SHA-256,
checked for the expected package/version, then verified by Android's installer.
There is no installer entry point exposed to website JavaScript.

## First rollout

Existing v1.2.6 installations do not contain the checker. Students must install
v1.2.7 once from the official release/download link. Future releases can be offered
inside the app. Do not uninstall the current app as the first step: try the signed
APK as an update over the existing installation, then check saved reminders.

## Publish a release

1. Edit `release.json`: increase `versionCode` for every release, choose a new
   `versionName` such as `1.2.8`, and write the release notes.
2. Build and sign using the SAME private signing backup and lineage described below.
3. Test installation over the previous app on a real phone. Check sign-in,
   timetable reminders, the Updates button, permissions, and a future-version prompt.
4. Create a GitHub Release with the exact tag `v<versionName>` (for example `v1.2.8`).
5. Attach BOTH `dist/SFT-Pavilion.apk` and `dist/update.json`. Keep these filenames.
   Publish it as the latest stable release. Keep older releases available because
   this recovered build depends on the fixed v1.2.6 baseline.

Upload the two generated files from the same build. `update.json` contains the APK's
actual digest and byte count. Do not manually invent those fields, upload a source
ZIP as the APK, or replace the v1.2.6 baseline asset. Draft and pre-release builds
do not belong in the stable update channel.

Publishing new website content is separate: deploy to the existing website hosting.
An APK rebuild is needed for changes to Android functionality.

## Build locally (Linux, JDK 17 and Python 3)

The build downloads hash-pinned Apktool 3.0.3, Android SDK Build Tools 35,
Android platform 35, and the original APK. No Gradle installation is required.

Unsigned compile verification:

```bash
python3 build.py
```

For a signed release, extract the PRIVATE signing backup into `private/` and run:

```bash
source private/build.env
python3 build.py \
  --keystore private/sft-release.jks \
  --old-key private/legacy-testkey.pk8 \
  --old-cert private/legacy-testkey.x509.pem \
  --lineage private/signing-lineage.bin
```

This produces the two release assets in `dist/`. The build verifies the resulting
APK signatures before generating metadata. `.cache/`, `build/`, `dist/`, and
`private/` are excluded from git. The signing backup is NOT in this repository.

## Signing recovery and supported Android versions

Inspection of the supplied APK's v1/v2 certificate identified the standard public
AOSP test certificate, SHA-256:

`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`

A new private RSA release key and a proof-of-rotation lineage have been created.
The build uses `--rotation-min-sdk-version 28`: Android 9+ can move to the private
signer through an update, while Android 6–8 use the original signer for compatibility.
Those older Android releases cannot obtain private-key protection through this
rotation path; a separately planned reinstall with a private-only build is needed
if that protection is required on older devices. Never use this public legacy
test key as the sole signer for a new production app.

Keep the private keystore, its password, and the lineage together in a secure backup.
Losing the private key can prevent future updates on migrated devices. Do not
upload the signing backup, `build.env`, or private keys to GitHub Releases or git.

## Checks

`python3 test.py` runs release-policy tests. The build also compiles the updater,
reassembles the original app, aligns it, and verifies its Android signing schemes.
These checks do not replace an installation and reminder test on an Android phone.
No phone/emulator installation has been performed for the initial recovered build.

The optional GitHub workflow compiles and tests unsigned builds; publishing and
private-key setup remain explicit owner actions.

Official references:

- https://developer.android.com/tools/apksigner
- https://source.android.com/docs/security/features/apksigning/v3
- https://developer.android.com/google/play/app-updates
