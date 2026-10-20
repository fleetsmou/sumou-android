# Build the Sumou Fleet driver APK

The packaged UI is in `www/`. It includes the driver UI, fonts, icons and pinned
Leaflet/Supabase browser libraries, so startup does not depend on a hosted HTML
page or third-party script CDN. Online operations and map tiles require internet.

The project keeps Capacitor at 5.7.8, Geolocation at 5.0.8, compile SDK 34, target
SDK 33 (the previous app's target), AGP 8.1.4 and app version 1.0.1 (versionCode 2).
Java 17, Node 20 or later, Python 3 and Android SDK command-line tools are needed.
The generated `android/` project and all private signing keys are excluded from
source control.

## Prepare and build

Set `JAVA_HOME` and `ANDROID_HOME` to your installed Java 17 and Android SDK.
Install Android platforms 34 and build tools 34.0.0, and accept the SDK licenses.
AGP can also install its default build tools 33.0.1 during compilation.

```sh
npm ci --legacy-peer-deps
npx cap add android
npx cap sync android
python3 scripts/configure-build.py
python3 scripts/configure-native.py
python3 tests/test-tracker-policy.py
cd android
chmod +x gradlew
./gradlew assembleRelease --no-daemon
cd ..
```

Use `npx cap sync android` and rerun both configure scripts when rebuilding an
existing generated platform. Complete sync/configuration before Gradle starts;
do not synchronize a platform while its build is running.

## Sign a release

Use the same private release keystore for every distributed update. The keystore
and its password file stay outside this repository and the public source bundle.
Do not commit them to GitHub or replace them with a newly generated key.

```sh
export SUMOU_RELEASE_KEYSTORE=/private/path/to/sumou-release.keystore
export SUMOU_RELEASE_PASSWORD_FILE=/private/path/to/sumou-release-password.txt
python3 scripts/sign-release.py /destination/sumou-fleet-1.0.1.apk
```

The signing alias is `sumou-fleet`. The script uses Android build tools 34.0.0,
aligns the unsigned release APK, signs it, verifies its signatures/alignment and
prints its SHA-256. The release build is not debuggable.

The previously supplied APK used a different Android debug certificate. This
signed release requires clocking out, uninstalling that old APK, installing this
release, and logging in again. Its bundled origin also differs from the old
hosted origin, so the ERP device binding may need an administrator reset. Future
updates built with this same release key can update this installation normally.

## Build with GitHub Actions

Every push to `main`, or a manual run of **Build Sumou Fleet APK**, builds the
committed `www/` snapshot and injects the committed native tracking source. It
does not fetch an older hosted driver page. The workflow runs the native policy
checks and produces two artifacts:

- `sumou-fleet-test-debug-apk`: debug APK for testing. Its runner-generated
  signing identity cannot update the signed release installed by drivers.
- `sumou-fleet-release-unsigned-apk`: release APK awaiting your existing private
  signing key. This cannot be installed until signed.

Download and extract the unsigned artifact, then sign it on the machine holding
the same private release key used for the distributed 1.0.1 APK:

```sh
export SUMOU_RELEASE_KEYSTORE=/private/path/to/sumou-release.keystore
export SUMOU_RELEASE_PASSWORD_FILE=/private/path/to/sumou-release-password.txt
python3 scripts/sign-release.py /destination/sumou-fleet-1.0.1.apk \
  --input /path/to/downloaded/app-release-unsigned.apk
```

Keep the existing release key and password backed up privately. Neither is
included in GitHub; a source checkout alone cannot recreate its signing identity.

## Refresh the web UI

The driver source stays in the Driver-s-App repository. To refresh the packaged
snapshot before building:

```sh
python3 scripts/bundle-driver.py /path/to/Driver-s-App
```

The bundler copies only app assets, excludes tests and Git metadata, and rewrites
Leaflet and Supabase CDN references to the bundled versioned libraries. Rebuild
and sign the APK after refreshing it.

## Verification

Native policy checks cover shift/session identity, GPS validation, heartbeat,
retry timing, and terminal authentication/closed-shift handling. The APK build
runs release lint checks. Physical Android testing remains necessary for Clock
in, lock screen, Home, Recents swipe, offline recovery, reopening the same shift,
Clock out, and manufacturer-specific battery restrictions. Force stop and user
permission revocation are Android-level stops, not continuity guarantees.
