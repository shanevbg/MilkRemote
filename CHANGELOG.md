# Changelog

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
