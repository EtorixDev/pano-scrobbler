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
- Metadata tag preservation across repeats, queued submissions, and the version
  22 to 23 database migration.
- Canonical track-progress timestamps and successful immediate edits across
  presence updates, resume, seek, and repeat. Delayed edits leave later playback
  occurrences and their queued submissions intact. Duration corrections retain
  edit ownership, while pending edits stay with the earlier play across repeats.
  Editor cancellation uses the corrected duration and submission deadline.
  Early updates preserve completed edits, and buffering preserves edit ownership.
  Long pauses and buffering followed by an unknown-position resume preserve
  completed edits and exclude the gap from playback timing.
  Transient unknown positions during continuous playback retain restart evidence.
- Historical edits for the additional custom ListenBrainz accounts, with
  cross-service synchronization enabled and disabled.
- Recognition presence lifetime, duplicate suppression, service-state clearing, and Android RPC
  request deadlines with a blocked worker.
- Release artifact validation against an explicit native build run and commit.
- Compiled fork identity and version numbers, checked against the version files
  and integrated upstream history.
- The existing theme preference, album artwork, and metadata promotion tests.
  The promotion test exercises session ownership, submitted tracks, pending
  work, and repeats with local edit rules. It also checks presence updates on
  submitted-track resume and seek, plus late metadata in ListenBrainz requests.

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
