# Fork Regression Checks

Run the focused suite from the repository root with JDK 25, the Android SDK, and
the usual local build configuration available:

```bash
bash scripts/test_fork.sh upstream/main
```

The script uses `JAVA_HOME` when set and otherwise uses the workstation's
`~/.local/graalvm/liberica-nik-25`. Pass a different local upstream ref as the
first argument when needed. The version check requires full Git history and
compares `version.txt` with the upstream commit already integrated into `HEAD`.
An upstream release that has not been merged does not require a version bump.

## Coverage

- Settings search focus on desktop entry and re-entry, plus visibility and
  persistence of the track progress and cross-service edit switches.
- Scrolling to the first track when the selected Recents or Loved tab is clicked.
- Public preference export/import, including fork values and legacy defaults.
- Edited track metadata preservation and removal of obsolete album metadata.
- Compiled fork identity and version numbers, checked against the version files
  and integrated upstream history.
- The existing theme preference, album artwork, and metadata promotion tests.
  The promotion test exercises session ownership, submitted tracks, pending
  work, and repeats with local edit rules.

The UI tests render the production composables offscreen in separate JVMs.
They use temporary profiles and local fixtures. The scrobbling lifecycle test
also runs in an isolated child JVM. No signed-in profile or desktop session is
required. Fixture directories and child logs remain under
`/tmp/pano-*-ui-*`, `/tmp/pano-media-listener-promotion-*`, and
`/tmp/pano-version-check-*` for inspection.

## CI And Releases

`fork-tests.yml` runs this suite on pull requests and pushes to `main`.
The release workflow requires the same checks before building artifacts.
CI fetches full upstream history and supplies dummy API credentials for the
local fixtures. JVM test reports are uploaded even when a check fails.

Add a regression test when a fork behavior can be exercised through observable
state, data, or UI interaction. Keep fixtures local and assertions focused on
the behavior that a merge must preserve. The full upstream JVM suite remains
available through `:composeApp:jvmTest` without test filters.
