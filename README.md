# SpotConnect Premium

Play your Spotify Premium library from inside Minecraft.

**Requires a Spotify Premium account.**

Minecraft 1.21.1 · Fabric · client-side only.

## How it works

```
Minecraft 1.21.1
      |
      v
Spotify Minecraft Mod
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
- **Your own free Spotify app.** See below.

## Using it

Press **M** (rebindable in Options → Controls). First run opens a 12-step walkthrough
that covers everything, including creating the Spotify app.

The walkthrough exists because a Spotify app in development mode only works for 25
accounts that the app owner adds by hand. A single shared app id would work for nobody
but me, and every user would share one rate-limit bucket. So each install brings its
own app - the walkthrough copies the redirect URI to your clipboard, checks the id you
paste, and connects at the end.

After that it is just **M**. Sign-in is remembered; you never repeat the setup.

You can redo it at any time from **Settings → Account → Reset setup**.

## Before you "clean up" any of this

Each of these looks like it could be simplified. None of them can. I hit every one of
these the hard way:

- **The four Chrome flags** in `browser/ChromeLauncher`
  (`CalculateNativeWinOcclusion`, `disable-backgrounding-occluded-windows`,
  `disable-renderer-backgrounding`, `disable-background-timer-throttling`).
  A window at -32000,-32000 is on no display, so Chrome's occlusion tracker marks it
  occluded, flips the page to hidden and backgrounds the renderer. A hidden page never
  starts media playback: progress stays frozen at 0 ms while the API still reports
  `is_playing=true`. Without all four there is no audio.
- **Profile-based process tracking** in `browser/ChromeTracker`. Chrome's launcher
  process routinely hands off and exits, so `Process.isAlive()` on it reports "dead"
  while the window is alive. Liveness is determined by finding a `chrome.exe` whose
  command line contains our `--user-data-dir`.
- **The four-tier device identification** in `spotify/SpotifyDeviceManager.pick()`.
  Stops the mod ever driving the Spotify Web Player in your *normal* browser. If two
  Web Players can't be told apart it does nothing at all.
- **The response-then-complete ordering** in the OAuth callback handler
  (`auth/SpotifyOAuth`). Completing the future first let the server be torn down
  mid-write, producing "127.0.0.1 refused to connect" on a *successful* authorization.
- **`synchronized` on token refresh** (`auth/SpotifyTokenManager`). Spotify rotates
  refresh tokens; two concurrent refreshes make the second present a consumed token and
  the session is lost.
- **No "session ok" marker file.** "This profile logged in once" is not evidence that
  it is authenticated *now*. Only a registered Connect device proves that.

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
liked songs), queue, playback control, album artwork, the in-world mini-player, DJ X,
playlist creation, and a settings screen.

Known limitations, none of which I can fix from here:

- **DJ X starts but the audio stalls** at ~1620ms on the web player. The API returns 204
  and reports `is_playing`. Probably gated to first-party clients.
- **No crossfade.** The Web API drives one Connect device with no way to overlap two
  streams, so the fade option is a volume ramp, not a real crossfade. Off by default.
- **`audio-features` and `audio-analysis` both 403** for this app, so the visualizer is
  synthesised motion rather than anything derived from the actual track.
- **Volume drifts during playback.** The web player re-asserts its own remembered volume
  mid-track (watched it walk 60 → 36 with every mod-side write disabled).
- **WebP covers fall back to a placeholder** - ImageIO has no WebP reader.

`archive/rinku-poc/` is the earlier embedded-browser experiment, kept as a record. It was
ruled out because the available CEF builds lack AAC, and Widevine VMP signing makes
Spotify DRM unusable in an unsigned embedded browser.

## License

MIT. See [LICENSE](LICENSE).

Not affiliated with or endorsed by Spotify. "Spotify" is a trademark of Spotify AB.
You need your own Spotify Premium account and your own client ID.
