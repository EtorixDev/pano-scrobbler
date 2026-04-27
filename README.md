## Pano Scrobbler ETD
This is a fork of [Pano Scrobbler](https://github.com/kawaiiDango/pano-scrobbler) by [kawaiiDango](https://github.com/kawaiiDango/).

Donate to the original developer here:
- https://ko-fi.com/kawaiiDango
- https://buymeacoffee.com/kawaiiDango

---

**This fork is unstable**.

If you utilize this fork you agree not to report bugs in the upstream repository before verifying they also exist in official releases, as well as agreeing to not rate the app poorly through official release channels for bugs or changes introduced in this fork.

Changes:
- Can be ran side-by-side with original.
- Premium features have been unlocked.
- Billing routes & crash analytics removed.
- Releases and in-app update checks are GitHub-only.
- App icon & default theming changed to blue.
- Live theme previewing rather than needing to save which backs out of the themes page.
- Refresh recents on track change rather than on 30 second interval.
  - Refreshes regardless of focus on desktop.
  - Refreshes only while app opened for mobile.
  - Will refresh up to 3 times after a track change at 3, 6, and 9 seconds post update until a refresh succeeds in receiving new data.
- Edit the actively playing track.
  - Will scrobble immediately after an edit is saved.
  - If you need to edit it again, you must edit the scrobbled entry, as the active entry is now treated as no longer scrobble-pending.
- More robust album art preservation when editing a track.
- Mobile widget now supports more refresh intervals (1, 2, 4, 6, 12, 24) instead of always 4 hours.
- Prevent duplicate Last.fm scrobbles in the rare case your edit fails due to needing to reauthenticate.
  - Edit will be discarded and you will need to redo it.
- Scrobble threshold now based on track progress rather than time since app started tracking progress.
  - Meaning if you open the app 1 minute into a song, it will have 1 minute of tracking instead of starting at 0.
- Auto-focus settings search bar on desktop.
- Adjust padding of various elements.

Notes:
- Spotify support for artist image and album art tools is disabled in releases from this repo.
  - Spotify Web API requires a premium subscription from the app maintainer. As such, releases from this repo do not ship Spotify API credentials.
  - If you want those features, build your own copy and set `spotify.refreshToken` in `local.properties`.
  - See [instructions.md](./instructions.md) for building steps.

---

<img src="desktop-screenshots/1-scrobbles-desktop.jpg" alt="scrobbles screen" width="250"/> <img src="desktop-screenshots/2-charts-desktop.jpg" alt="charts screen" width="250"/>

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3-friends-mobile.jpg" alt="friends screen" width="150"/> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4-details-mobile.jpg" alt="details screen" width="150"/>

### Downloads

**Windows:**

[![github-x64](img/github-x64.svg)](https://github.com/EtorixDev/pano-scrobbler/releases/latest/download/pano-scrobbler-etd-windows-x64.exe)

**Linux:**

[![github-x64](img/github-x64.svg)](https://github.com/EtorixDev/pano-scrobbler/releases/latest/download/pano-scrobbler-etd-linux-x64.AppImage)
[![github-arm64](img/github-arm64.svg)](https://github.com/EtorixDev/pano-scrobbler/releases/latest/download/pano-scrobbler-etd-linux-arm64.AppImage)

**Android (phones, tablets, TVs and Chromebooks):**

[![github](img/github.svg)](https://github.com/EtorixDev/pano-scrobbler/releases)

## [FAQ](faq.md) • [Privacy](privacy-policy.md) • [Compiling](instructions.md)

### Features:

#### For all platforms (Windows, Linux, Android, Android TV):

- No ads ever
- Scrobbles to Lastfm, Librefm, ListenBrainz, Pleroma and other compatible services
- View song, album, artist, album artist, and tag details
- View scrobbles from a specific time, such as last year or last month
- Extract or fix metadata such as "Remastered" with regex pattern edits
- Extract the first artist from a string of all performers before scrobbling
- Block artists, songs, etc., and automatically skip or mute when they play
- Check what your followed users are listening to and view their stats
- Import and export settings, edits, and blocklists
- View charts with change indicators for specific time periods,
- View scrobble count graphs and tag clouds
- Get a random song, album, or artist from your listening history
- Search Lastfm for a songs, artists, or albums
- Themes
- Remember and see apps you scrobbled from and play directly in them
- Proxy support

#### For desktop and Android (except TV):

- Scrobble to a CSV or JSONL file locally
- Interactive notification - view song info, edit, love, cancel, or block songs directly from the
  notification
- Collage generator
- Add or remove personal tags from the info screen
- Edit or delete existing scrobbles. Remembers edits
- Control Pano Scrobbler ETD from automation apps on Android or command-line on desktop

#### Android only (except TV):

- Scrobble from music recognition apps: Shazam, Ambient Music Mod and Audile
- Scrobbling the new Pixel Now Playing app (since the March 2026 Pixel feature drop) is possible
  only with root and KieronQuinn's Xposed module
  [Public Compute Services](https://github.com/KieronQuinn/PublicComputeServices)
  [\[Why?\]](https://github.com/kawaiiDango/pano-scrobbler/issues/876)
- Charts as a customizable home-screen widget
- Get your top scrobbles digests as a notification at the end of every week, month and year

#### Desktop only:

- Customizable Discord Rich Presence

### Credits

- YouTube title parser from [Web Scrobbler](https://github.com/web-scrobbler/web-scrobbler)
  and [Metadata Filter](https://github.com/web-scrobbler/metadata-filter)
- Icons from [pictogrammers.com](https://pictogrammers.com) and [material.io](https://material.io)
- Genres filter from [everynoise.com](https://everynoise.com)
- Tidal SteelSeries Integration from [TidalRPC](https://github.com/BitesizedLion/TidalRPC)
- Artists list from [MusicBrainz](https://musicbrainz.org)

Thanks to the
amazing [translators](composeApp/src/commonMain/composeResources/files/crowdin_members.txt) and
everyone who reported bugs and helped me with this project.

### Disclaimer

This project is not affiliated with Last.fm, Libre.fm, ListenBrainz or any other scrobbling service.

### License

SPDX-License-Identifier: GPL-3.0-or-later

Pano Scrobbler ETD is licensed under
the [GNU General Public License v3 or later](http://www.gnu.org/copyleft/gpl.html).
