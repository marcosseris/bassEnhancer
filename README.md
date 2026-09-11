# Bass Enhancer

An Android app that listens to whatever media your phone is playing and drives the
vibration motor in time with the low end, so the Pixel's linear actuator adds the
rumble that a phone speaker physically cannot produce.

It runs as a small foreground service. Two threads do the work — one blocked on the
audio buffer, one ticking the actuator — and the analysis is a band-pass filter plus
a pair of envelope followers, so there is no FFT and no per-buffer allocation.

## Getting it

Every push builds an APK and attaches it to a GitHub release. Grab the newest one
from the [Releases page](../../releases) and install it.

`BassEnhancer-<version>.apk` is the optimised build; the `-debug` variant is
unminified and worth trying if something behaves oddly.

> **Signing:** unless a `KEYSTORE_BASE64` secret is configured (see below), CI mints
> a throwaway signing key per build. The APK installs fine, but Android will refuse
> to upgrade over a previously installed copy — uninstall first, or set the secret.

## Using it

1. Open the app and hit **Start**.
2. Allow the microphone permission. Android routes playback capture through it; no
   microphone audio is ever read.
3. Accept the screen-capture consent prompt. This is the only API Android offers for
   reading the media audio stream. Nothing is recorded and nothing leaves the device.
4. Play something with bass in it and tune the sliders while you listen.

There is also a **Quick Settings tile** for toggling it without opening the app.

### Why a screen-capture prompt?

`AudioPlaybackCapture` is gated behind `MediaProjection`, and `MediaProjection`
consent is presented as a screen-capture dialog regardless of whether you ask for
video. The app requests audio only.

### Capture modes

| Mode | Consent prompt | Coverage |
| --- | --- | --- |
| **Playback capture** (default) | Once per session | Every app except those that opt out of capture — Spotify and most DRM video deliver silence by design |
| **Visualizer** (experimental) | None | The whole output mix, but many Android builds return a flat line. Try it if playback capture is silent. |

Visualizer mode is also the only mode that can start on boot: a `MediaProjection`
grant cannot survive a reboot.

## Settings

**Trigger**

- **Kick in at** — the level, in dBFS, below which nothing plays. The meter on the
  main card shows the live band level with this threshold marked, so you can set it
  by eye against your own music.
- **Intensity** — overall strength multiplier.
- **Maximum strength** — hard ceiling on the actuator.
- **Adaptive gain** — normalises against recent loudness so quiet tracks still
  rumble. Off gives a literal, dynamics-preserving response.

**Frequency band**

- **Low cut** / **High cut** — the band that drives the haptics. 20–160 Hz is kick
  and bass guitar; raising the high cut to ~250 Hz brings snares in too.

**Feel**

- **Style** — *Hybrid* (rumble plus a tick on each kick), *Rumble* (pure envelope
  tracking), or *Punch* (silent except for a thump per kick).
- **Attack** / **Release** — envelope times. Short attack is punchier; long release
  is smoother.
- **Kick sensitivity** — how sharp a transient has to be to count as a kick.
- **Update interval** — how often the actuator is refreshed. Higher saves battery,
  lower tracks the music more tightly.
- **Test vibration** — a three-step ramp so you can feel a setting immediately.

**Battery and behaviour**

- Pause while the screen is off, pause during calls, stop below a battery level, and
  start on boot.

## Overhead

- Analysis is four biquads and three one-pole followers per sample — a few
  microseconds per audio buffer.
- The actuator is only re-issued when the amplitude actually moves or the current
  pulse is about to expire, so steady material costs a handful of binder calls a
  second rather than one per tick.
- The meter is throttled to ~10 Hz so the UI is not recomposing at audio rate, and
  it stops entirely when the app is not in the foreground.

## Building locally

```sh
./gradlew :app:assembleDebug
```

Requires JDK 17 and an Android SDK with API 35. `assembleRelease` falls back to the
debug signing identity when no `keystore.properties` is present.

## Stable signing (optional)

Generate a keystore and add it to the repository secrets so every build is signed
with the same key and can upgrade in place:

```sh
keytool -genkeypair -v -keystore release.jks -alias bassenhancer \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks
```

Then set these repository secrets: `KEYSTORE_BASE64` (the base64 output),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Requirements

Android 10 (API 29) or newer. Amplitude control and haptic primitives are used when
the device reports them — the app tells you what your hardware supports in the
*Hardware* section — and falls back to fixed-strength pulses when it does not.
