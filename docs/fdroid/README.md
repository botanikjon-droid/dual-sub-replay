# F-Droid distribution

DualSub Replay has two builds:

| Build | Command | Translation |
| --- | --- | --- |
| Full (GitHub releases, previews) | `bash ./gradlew assembleRelease` | Google ML Kit, on-device |
| F-Droid | `bash ./gradlew assembleRelease -Pdistribution=fdroid` | Mozilla Bergamot with Firefox Translations models, on-device |

The switch picks `app/src/full/java` or `app/src/fdroid/java` and only the full build depends
on ML Kit. Task names and APK paths stay the same.

## The F-Droid translation engine

- Source: git submodules in `app/src/fdroid/cpp`: `translations` (mozilla/translations, whose
  `inference/` folder is Bergamot and Marian, MPL-2.0) and `pcre2` (PCRE2 10.44, BSD).
  Clone with `git submodule update --init --recursive`; Marian's CMake also initialises its
  own nested submodules if they are missing.
- Build: `app/src/fdroid/cpp/CMakeLists.txt` plus a small JNI bridge (`bergamot_jni.cpp`),
  compiled with NDK r28c (`28.2.13676358`) and CMake 3.22.1 for `arm64-v8a` and `x86_64`.
  NDK r29's Clang rejects code in the bundled SentencePiece, so stay on r28 until that is fixed
  upstream. Native code is always built optimised; one ABI takes a few minutes.
- Models: downloaded on first use from Firefox Remote Settings (the same MPL-2.0 models Firefox
  uses), checked against their SHA-256 and kept in app storage (`files/translation-models`).
  Every model goes to or from English, so for example Japanese→Vietnamese downloads two models
  and pivots through English. Subtitle text never leaves the device.
- Not supported: 32-bit ARM phones (`armeabi-v7a`) show an "offline translation is not
  supported on this device" error, and languages Mozilla has no model for show "not available
  for offline translation".

## Checks

- `fdroid-build` CI job: lints and builds the F-Droid release APK, requires both native
  libraries, and fails if the APK contains ML Kit, Play Services or Firebase classes.
- `fdroid-device-tests` CI job: downloads the English↔Vietnamese models with
  `tools/fetch_bergamot_test_models.py` into the test APK's assets and runs
  `BergamotEngineDeviceTest` on the API 36 emulator (translate, and pivot through English).

Store listing text and images live in [`fastlane/metadata/android`](../../fastlane/metadata/android),
where F-Droid reads them. [`com.kienhoang.dualsubreplay.yml`](com.kienhoang.dualsubreplay.yml)
is a draft of the recipe that goes into F-Droid's fdroiddata repository.

## Before submitting

1. Merge and release, so a `vX.Y.Z` tag contains the F-Droid build. Fill that tag's version
   name and code into the recipe.
2. Check the recipe locally with `fdroid lint`, `fdroid scanner` and `fdroid build` from
   fdroidserver, inside a checkout of fdroiddata (its `config/` defines valid categories).
   fdroiddata wants the tag's full commit hash in `commit`, not the tag name.
3. Fork fdroiddata on GitLab, add the recipe as `metadata/com.kienhoang.dualsubreplay.yml`, and
   open a merge request. F-Droid reviewers usually reply with changes to make.

After it is accepted, new `v*` tags are picked up automatically. F-Droid signs its APK with its
own key, so it cannot update a GitHub install (or the other way round) without reinstalling.
