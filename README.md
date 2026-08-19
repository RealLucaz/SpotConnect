# SpotConnect Premium

Play your Spotify Premium library from inside Minecraft.

**Requires a Spotify Premium account.**

Minecraft 26.1.2 · Fabric · client-side only.

## How it works

```
Minecraft 26.1.2
      |
      v
SpotConnect Premium
      |
      +-- ui/          setup walkthrough, library UI, player (Minecraft-native)
      +-- auth/        OAuth2 PKCE, token refresh, Web Player sign-in flow
      +-- spotify/     Web API client, device identification, playback commands
      +-- browser/     dedicated Chrome: launch, track, show/hide, page state
                |
                v
      dedicated hidden Chrome  (isolated profile, real non-headless Chrome)
                |
                v
      open.spotify.com  =  a Spotify Connect device
                |
                v
      Spotify audio -> your normal audio output
```

The Chrome window is the **playback engine**, not the interface. It lives off-screen with
no taskbar button and is only ever brought forward when Spotify genuinely needs a sign-in.
The Spotify **desktop app does not need to be running**.

## Requirements

- **Windows.** The browser layer uses PowerShell and Win32 APIs. On macOS or Linux the
  mod loads but tells you it cannot run, rather than failing halfway.
- **Spotify Premium.** The Web API playback endpoints are Premium-only.
- **Google Chrome** installed.
- **Java 25**, required by Minecraft 26.1.2.
- **Fabric Loader 0.19.3+** and **Fabric API**.
- **Your own free Spotify app.** Created during first-run setup; see below.

## Using it

Press **M** (rebindable in Options → Controls). First run opens a 12-step walkthrough
that covers everything, including creating the Spotify app.

The walkthrough exists because a Spotify app in development mode serves only 25 accounts,
each added by hand by the app owner. A shared app id would therefore fail for everyone
except the owner, and all users would draw on one rate-limit bucket. Each install
registers its own app instead. The walkthrough copies the redirect URI to the clipboard,
validates the pasted id, and connects at the end.

After that it is just **M**. Sign-in is remembered; you never repeat the setup.

You can redo it at any time from **Settings → Account → Reset setup**.

## Implementation constraints

Each of the following is load-bearing. The reasoning is recorded here because none of it
is obvious from the code alone.

- **The four Chrome flags** in `browser/ChromeLauncher`
  (`CalculateNativeWinOcclusion`, `disable-backgrounding-occluded-windows`,
  `disable-renderer-backgrounding`, `disable-background-timer-throttling`).
  A window at -32000,-32000 sits on no display, so Chrome's occlusion tracker marks it
  occluded, flips the page to hidden and backgrounds the renderer. Hidden pages do not
  start media playback: progress stays at 0 ms while the API reports `is_playing=true`.
  All four flags are required for audio.
- **Profile-based process tracking** in `browser/ChromeTracker`. Chrome's launcher
  process hands off and exits, so `Process.isAlive()` on it reports dead while the
  window is still running. Liveness is determined by locating a `chrome.exe` whose
  command line contains the mod's `--user-data-dir`.
- **The four-tier device identification** in `spotify/SpotifyDeviceManager.pick()`.
  Prevents the mod from driving a Spotify Web Player in the user's normal browser.
  Two indistinguishable Web Players result in no action being taken.
- **The response-then-complete ordering** in the OAuth callback handler
  (`auth/SpotifyOAuth`). Completing the future first allows the server to be torn down
  mid-write, producing "127.0.0.1 refused to connect" on a successful authorization.
- **`synchronized` on token refresh** (`auth/SpotifyTokenManager`). Spotify rotates
  refresh tokens. Concurrent refreshes cause the second to present a consumed token,
  which loses the session.
- **No session-ok marker file.** A profile having logged in once is not evidence of
  current authentication. Only a registered Connect device establishes that.

## Security

- The mod never asks for, reads, types, stores or transmits a Spotify password.
- No client secret exists anywhere; this is a public client using PKCE.
- Nothing is copied from any other Chrome profile.
- No audio is captured, recorded, downloaded or routed.
- The DevTools port is used read-only, to ask Chrome which URL is open, so the mod can
  tell whether Spotify is showing the login form, the verification step, or the player.

## Building

```
gradlew build
```

Output: `build/libs/spotconnect-0.1.0.jar`. Run the dev client with `gradlew runClient`.

## Status

Working: connect, sign-in, search, browse (home / library / playlists / albums / artists /
liked songs), queue, playback control, album artwork, the in-world mini-player, DJ X, and
a settings screen.

Known limitations. All of these are Spotify API restrictions rather than mod defects:

- **Playlist track lists are unavailable.** `GET /v1/playlists/{id}` returns the playlist
  without a `tracks` key, and `GET /v1/playlists/{id}/tracks` returns 403. This applies to
  playlists the user owns as well as public ones. Playback still works, because starting a
  context does not require reading its contents.
- **DJ X starts but the audio stalls** at ~1620 ms on the web player. The API returns 204
  and reports `is_playing`. Likely gated to first-party clients.
- **No crossfade.** The Web API drives a single Connect device with no way to overlap two
  streams. Overlapping playback is not achievable from outside the player.
- **`audio-features` and `audio-analysis` both return 403** for this application, so the
  visualizer is synthesised motion rather than anything derived from the track.
- **Volume drifts during playback.** The web player re-asserts its own remembered volume
  mid-track. Measured falling from 60 to 36 with every mod-side write disabled.
- **WebP covers fall back to a placeholder.** ImageIO has no WebP reader.

Playback does not use an embedded browser. That approach was tried and ruled out: the
available CEF builds lack AAC, and Widevine VMP signing makes Spotify DRM unusable in an
unsigned embedded browser.

## License

MIT. See [LICENSE](LICENSE).

Not affiliated with or endorsed by Spotify. "Spotify" is a trademark of Spotify AB.
You need your own Spotify Premium account and your own client ID.
