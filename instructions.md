# Build Instructions

Welcome to my spaghetti

Put these in local.properties
```properties
# // https://www.last.fm/api/account/create
lastfm.key=
lastfm.secret=
# Optional: Spotify client credentials for Spotify-backed artist image / album art features
# in self-built copies. Value is base64(client_id:client_secret).
# // https://developer.spotify.com/dashboard/
spotify.refreshToken=<base64 encoded client_id:client_secret>

# Local Android SDK path.
# Needed for Android builds on your own machine.
sdk.dir=/absolute/path/to/your/Android/Sdk

# Android releaseGithub signing config.
# Android APKs must be signed to install.
# These are required only if you want to build the signed releaseGithub APK with
# androidApp:assembleReleaseGithub.
# They are not needed for a normal debug install (`androidApp:installDebug`).
releaseGithub.keystore=/absolute/path/to/your-signing-key.jks
releaseGithub.storePassword=
releaseGithub.alias=
releaseGithub.password=

# Optional: override updater availability.
# This only controls whether self-built release packages should offer in-app
# update checks against this fork's GitHub releases.
# releaseGithub and packaged desktop builds default to true.
# Source/debug builds default to false.
# updates.enabled=true
```

`releaseGithub.*` controls the signing key for the signed Android release APK.
Android only allows one installed app lineage per package name, so the signing key must stay
consistent if you want users to install newer APKs over older ones.

`sdk.dir` points Gradle at your local Android SDK installation. It is needed for Android builds on
your machine. On GitHub Actions you do not need to provide it in secrets because the workflow
writes the runner's Android SDK path into `local.properties` automatically.

- `releaseGithub.keystore`: path to the keystore file.
- `releaseGithub.storePassword`: password for opening the keystore.
- `releaseGithub.alias`: alias of the signing key inside the keystore.
- `releaseGithub.password`: password for that alias.

For personal local-only builds you can use a debug build instead, or point `releaseGithub.*` at a
debug keystore. For public releases you should use a dedicated signing key and keep it stable
across releases.

If you want a personal-use debug keystore for `assembleReleaseGithub`:

- If you already have Android Studio or have built Android apps before, you may already have one at
  `~/.android/debug.keystore`.
- If not, you can generate one with:

```bash
keytool -genkeypair \
  -v \
  -keystore debug.keystore \
  -alias androiddebugkey \
  -storepass android \
  -keypass android \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

- Then point `local.properties` at it:

```properties
releaseGithub.keystore=/absolute/path/to/debug.keystore
releaseGithub.storePassword=android
releaseGithub.alias=androiddebugkey
releaseGithub.password=android
```

Use that only for personal/local builds.

### For Android (FOSS):

- Run `./gradlew androidApp:assembleReleaseGithub`

- `releaseGithub` builds are intended for distributable APKs and enable the in-app updater against
  this fork's GitHub releases by default. If you are making a personal build and do not want that,
  set `updates.enabled=false` in `local.properties`.

- If you want to generate the optional custom baseline profile for the app, which can improve its
  startup time, create a file
  `/baselineprofile/src/main/java/dev/etorix/panoscrobbler/baselineprofile/Secrets.kt`:

```
object Secrets {
    const val type = "lastfm"
    const val username = "<lastfmUsername>"
    const val sessionKey = "<lastfmSessionKey>"
}
```

sessionKey can be obtained by logging in to LastFM with a debug build of this app
and tapping on the "Copy last.fm session key" in the settings screen.

Then run `./gradlew :baselineprofile:generateBaselineProfile`

Currently, the builds skip this step.

### For desktop:

- Rebuild the sibling repo https://github.com/EtorixDev/pano-native-components and copy its
  outputs into this repo before packaging desktop builds.

  - If the repos are checked out side-by-side, run one of the helper scripts from the
    `pano-native-components` repo root:
    - Linux: `./copy-builds-to-pano-dir.sh`
    - Windows: `copy-builds-to-pano-dir.bat`
  - On Linux, the native build needs `libwebkit2gtk-4.1-dev`.
  - Those scripts copy the built libraries into `composeApp/resources/<platform>`, which is where
    desktop packaging expects them.

- If you intend to package a build for desktop,
  use [Bellsoft's GraalVM based on OpenJDK 25](https://bell-sw.com/pages/downloads/native-image-kit/)
  as your JAVA_HOME and GRAALVM_HOME (both should be set). Also
  have [Inno Setup](https://jrsoftware.org/isdl.php) installed in Program Files on Windows.

- Run `./gradlew composeApp:exportLibraryDefinitions composeApp:packageUberJarForCurrentOS -PaboutLibraries.exportVariant=desktop`

- Packaged desktop builds also enable the in-app updater against this fork's GitHub releases by
  default. Set `updates.enabled=false` in `local.properties` if you want to suppress that for a
  self-built package.

### GitHub Actions release workflow

The workflow downloads the latest matching native desktop library artifacts from
`EtorixDev/pano-native-components`, so CI releases do not depend on locally copied `.so` or `.dll`
files from this repo checkout.

If you want the `Build binaries` workflow to create release artifacts on GitHub Actions, configure
these repository secrets first:

- `LOCAL_PROPERTIES`: contents of a minimal `local.properties` file. It should include at least
  `lastfm.key`, `lastfm.secret`, and any optional values you want such as `spotify.refreshToken`.
  If you are building the Android release on Actions, it should also include
  `releaseGithub.storePassword`, `releaseGithub.alias`, and `releaseGithub.password`.
  It does not need `releaseGithub.keystore`.
- `ANDROID_KEYSTORE_JKS`: your Android signing keystore file, base64-encoded. The workflow writes
  it to `androidApp/android-keystore.jks` and overrides `releaseGithub.keystore` to point there,
  so the secret does not need to contain a machine-specific keystore path.

What `releaseGithub.keystore` means on GitHub Actions:

- The workflow creates a file named `androidApp/android-keystore.jks` inside the temporary GitHub
  Actions checkout.
- That file is created by decoding your `ANDROID_KEYSTORE_JKS` secret.
- Then the workflow appends `releaseGithub.keystore=androidApp/android-keystore.jks` to
  `local.properties` so Gradle knows where that temporary file is.

In other words, `androidApp/android-keystore.jks` is just the temporary file path used on the CI
runner for your own signing key.

`ANDROID_KEYSTORE_JKS` is the binary `.jks`/`.keystore` file itself, base64-encoded so it can be
stored as a GitHub secret. For example:

```bash
base64 -w 0 your-signing-key.jks
```

Then paste the single-line output into the GitHub secret value.

### First-time setup for GitHub Releases

If you have never made an Android signing key before, do this once on your own machine.

1. Generate a signing keystore.

```bash
keytool -genkeypair \
  -v \
  -keystore pano-release.jks \
  -alias pano \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

This command will ask you to choose:

- a keystore password
- a key alias, if you do not want to use `pano`
- a key password (it might just automatically reuse the keystore value)

Save all of these. You will need them again for every future Android release.

2. Convert that keystore file into a GitHub secret value.

```bash
base64 -w 0 pano-release.jks
```

Copy the output.

3. In your GitHub repository, go to Settings -> Secrets and variables -> Actions and create:

- secret `ANDROID_KEYSTORE_JKS`: paste the base64 output from the previous step
- secret `LOCAL_PROPERTIES`: a text blob like this:

```properties
lastfm.key=YOUR_LASTFM_KEY
lastfm.secret=YOUR_LASTFM_SECRET
spotify.refreshToken=replace-me
releaseGithub.storePassword=YOUR_KEYSTORE_PASSWORD
releaseGithub.alias=pano
releaseGithub.password=YOUR_KEY_PASSWORD
```

If you used the same password for both the keystore and key, both password lines can have the same
value.

4. Run the `Build binaries` workflow.

- If you want build artifacts only, leave `Upload to GitHub Releases?` unchecked.
- If you want an actual GitHub release page with downloadable files, check it.

5. Keep the `pano-release.jks` file and its passwords somewhere safe.

If you lose that key, you will not be able to publish future Android updates that install over the
old ones.
