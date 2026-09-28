# F-Droid distribution

DualSub Replay has two builds:

| Build | Command | Translation |
| --- | --- | --- |
| Full (GitHub releases, previews) | `bash ./gradlew assembleRelease` | Google ML Kit, on-device |
| F-Droid | `bash ./gradlew assembleRelease -Pdistribution=fdroid` | No proprietary code; engine not chosen yet |

The switch only changes `app/src/full/java` vs `app/src/fdroid/java` and whether ML Kit is a
dependency. Task names and APK paths stay the same. The `fdroid-build` CI job builds the F-Droid
APK on every PR and fails if it contains ML Kit, Play Services or Firebase classes.

Store listing text and images live in [`fastlane/metadata/android`](../../fastlane/metadata/android),
where F-Droid reads them. [`com.kienhoang.dualsubreplay.yml`](com.kienhoang.dualsubreplay.yml)
is a draft of the recipe that goes into F-Droid's fdroiddata repository.

## Before submitting

1. Give the F-Droid build a free translation engine (see the
   [spec](../specs/2026-09-28-fdroid-distribution.md#open-decision-f-droid-translation-engine)).
2. Merge and release, so a `vX.Y.Z` tag contains the switch. Fill that tag's version name and
   code into the recipe.
3. Check the recipe locally with `fdroid lint` and `fdroid build` from fdroidserver.
4. Fork fdroiddata on GitLab, add the recipe as `metadata/com.kienhoang.dualsubreplay.yml`, and
   open a merge request. F-Droid reviewers usually reply with changes to make.

After it is accepted, new `v*` tags are picked up automatically. F-Droid signs its APK with its
own key, so it cannot update a GitHub install (or the other way round) without reinstalling.
