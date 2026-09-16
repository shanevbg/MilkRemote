# Changelog

## v1.4.0 — 2026-09-16

> **Requires MDropDX12 v3.1.0 or newer.** Almost everything below talks to
> commands that older releases do not have. The app asks the PC what it
> supports when it connects and hides what that PC cannot do, rather than
> offering a control that would be refused — so an older MDropDX12 still works,
> with less on screen.

### New — the Mixer tab

Your Windows volume, per application and per device, from the phone.

- **Every fader the PC's mixer draws, in the order it draws them.** The PC owns
  the arrangement and the phone renders it literally rather than regrouping it,
  so the two always agree.
- **Drag to reorder**, and the move is sent to the PC — the arrangement follows
  you rather than living on one device.
- **Coarse and fine steps** on every fader: 1 for trimming, 10 for getting
  there.
- **Mute where muting is possible.** A fader the PC reports as unmutable shows
  no mute control, instead of a button that does nothing.
- **Device names and headset battery.** A wireless headset's charge is shown on
  the row of the fader that controls it, under the name you gave the device.
- **Hotkey slots appear as markers on the faders they point at**, named after
  the keys they belong to, rather than as rows of their own.
- **Link faders to the volume keys.** Pick any set of faders and the phone's
  volume rocker moves them together. Which faders are linked is remembered per
  phone, since different phones sit in different places.
- **It stays live.** The list re-subscribes after a reconnect and follows
  changes made at the PC, and it does no mixer work at all while nothing is
  linked and the tab is closed.

### New — background operation

- **Stay connected while the app is in the background**, so the volume keys and
  the remote keep working with the screen off or another app in front. Optional,
  and off by default.
- **Be a media device while connected.** The phone can present itself as a
  remote volume target, and stop being one shortly after the connection drops,
  so it does not sit there claiming keys with nothing behind them.
- **The volume keys reach the linked faders from any screen**, not only the
  Mixer tab.
- **Come back to the tab you left.** Returning after Android has paused or slept
  the app lands where you were.

### New — Displays

- **Rebuilt around a monitor map.** Your desktop drawn to scale from the real
  monitor geometry, including monitors placed left of or above the main one. Tap
  a monitor to configure it; the one running the main window is marked, and a
  disabled one is crossed through.
- **Four tiers per display** — Off, Mirror, or its own preset, with that preset
  drawn either by the main window or by an instance of its own. The second costs
  more GPU and keeps running if the main window stalls, and the switch says so.
- **Watermark one display at a time** — dimmed and click-through on that screen
  alone, rather than every mirror together.
- **Bring a display to the front, or send it to the back**, without taking
  focus, so a screen given over to the visualiser can be pushed behind your
  working windows again from the phone.
- **Saved arrangements.** Save the whole display layout by name, load one back,
  and choose which one loads when MDropDX12 starts.
- **One control for every screen** — foreground and fullscreen every display at
  once, for walking away from the keyboard — plus a screensaver control that
  runs the PC's idle action on demand. Both are Quick Settings tiles too, so
  they work from the shade without opening the app.
- **Move the main render to another display** from the map, rather than a
  dropdown that always claimed Display 1.
- **Per-display preset control** — step one display forwards or back on its own,
  with its own random-or-sequential order and its own cycle interval.
- **Step the main window's preset without moving the independent displays**,
  which is what you want while setting up.
- **The main window's cycle time and order** are settable from this tab, with
  the inheritance spelled out: displays with no interval of their own follow it.
- **Per-display controls that were never sent before** — enable, opacity,
  click-through and independent render, each addressed to one display.

### New — Presets tab

- **Browse, search and load presets from the phone.** Groups are the folders the
  presets actually live in, with counts; a preset in more than one place appears
  in each. Sort by name, rating, most played, most watched or last played. The
  list pages as you scroll, so a large collection stays responsive.
- **Load a preset onto any display** — pick *Main* or a specific display, so the
  browser chooses what each screen shows, not just what the main window shows.
- **Rate, flag and tag from the row**, without loading the preset first, so
  curating no longer means changing what is on screen mid-show. Tags you add
  become groups you can browse by.

### New — knowing what the PC can do

The app asks MDropDX12 what it is and what it supports when it connects, and
gates each control on the capability it needs. Where something is missing it
says so, instead of quietly doing less or offering a button the PC will refuse.

### Bug Fixes

- **A dead address is no longer retried forever.** A PC with two addresses on
  one subnet — a virtual switch carrying the traffic while the physical adapter
  keeps a lease it no longer answers on — publishes both, and the phone could
  latch onto the one that goes nowhere. Nothing reports an error in that case,
  because the address is routable and the packets simply vanish. The phone now
  prefers an address it has actually received a packet from, moves to another
  candidate when one fails rather than retrying it, and remembers the one that
  worked.
- **A silent server left the app reconnecting forever.** A connection that was
  accepted and then never spoke parked the retry loop on a single attempt that
  could not resolve either way.
- **A slow or cancelled connect could strand the app disconnected** with a
  perfectly good address in hand, and two retry loops could run at once and
  fight over the same connection.
- **Commands survive a reconnect**, and a superseded connection attempt no
  longer clobbers the live one — which used to send commands down one socket
  while replies arrived on another.
- **The background service crashed the app on every launch** on Android 14 and
  newer, which requires a second permission for a connected-device service.
- **Volume and the wave sliders sent unusable values on some languages.** The
  app formatted decimals the way the phone's language does, so on German,
  French, Spanish, Russian or Portuguese it sent `0,50` where the PC expected
  `0.50` — and the PC read every one of those as zero. Volume, and the wave,
  zoom, warp, rotation and decay sliders, did nothing at all on those devices.
- **The connection header was drawn underneath the status bar**, so the
  connection state and preset name sat behind the clock. Introduced by the
  Android 16 targeting change in v1.2.0.
- **"Move to Display" could move the render window to the wrong monitor.** That
  command counts monitors by position rather than by their Windows display
  number, and the two only agree when displays are numbered 1..n with none
  missing.
- **The main display can no longer be given its own instance** — it already
  renders a preset, and doing so started a second copy on top of the main
  window.
- **Several display controls were talking to nothing.** Bring-to-front,
  keep-on-top and the watermark controls were addressed through a relay that
  MDropDX12 no longer answers for in-process displays. They use the verbs that
  exist now.
- **The displays list could show the wrong tier.** A display drawing its own
  preset in the main process runs no separate instance, and was being reported
  as a mirror.

## v1.3.0

### New Features
- **Button templates** — add buttons from 80+ predefined MDropDX12 actions organized into 12 categories (Presets, Media, Window, Display, Opacity, Visuals, Effects, Quality, Spout, Info/Debug, open Windows). Searchable template picker with one-tap add.
- **Developer documentation** — setup guides for Ubuntu 24.04, Fedora 43, and Windows 11 covering JDK, SDK, build, and ADB deploy. Architecture guide with walkthrough for adding controls, screens, and button types.

## v1.2.0

### Bug Fixes
- Fix TCP connection instability causing duplicate connections
- Fix connection header always showing "Not connected" despite being connected

### Other
- Set launcher icon in manifest
- Bump targetSdk to 36 for Android 16 / Pixel 10

## v1.1.0

### New Features
- Release signing configuration
- Volume controls, mute toggle, audio device picker
- Reorderable collapsible sections
- Authorization state display with connect/disconnect guards
- UDP beacon listener for reliable server discovery
- mDNS auto-discovery in Settings screen
- mDNS re-discovery on reconnect failure
- Bottom navigation with Remote, Displays, Buttons, Settings tabs
- Custom button board with 6 action types, long-press edit, import/export

### Bug Fixes
- Gradle, theme, and BuildConfig build fixes

## v1.0.0

- Initial release
- TCP remote control for MDropDX12 visualizer
- Wave, color, audio, FFT parameter controls
- App launcher icon
