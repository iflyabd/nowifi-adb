# NoWiFi Wireless Debugging

LSPosed module that enables Android **Wireless debugging** with **no WiFi and no hotspot**
(e.g. over mobile data or Tailscale). Fork of
[Hotspot Wireless Debugging](https://github.com/droserasprout/io.drsr.hotspotadb)
(GPL-3.0, by Lev Gorodetskii) — all hotspot functionality is kept, plus a
**No-WiFi mode** switch injected into the Wireless Debugging screen.

## Download

Get the APK from
[Releases](https://github.com/iflyabd/nowifi-adb/releases) (no login needed).

## Requirements

- Android 15–16, Magisk + Zygisk, LSPosed (legacy Xposed API, e.g. LSPosed 1.x)
- Root

## Usage

1. Install the APK, enable the module in LSPosed for scopes `com.android.settings`
   and `system` (System Framework), reboot.
2. Settings → Developer options → Wireless debugging → turn on **No-WiFi mode**.
3. Turn on the main **Wireless debugging** switch (no WiFi needed).
4. Pair: `adb pair <device-ip>:<pairing-port> <code>`, connect:
   `adb connect <device-ip>:<port>` (use the IP shown, or your Tailscale IP).

## Building

Pushes to `main` build a debug APK via GitHub Actions (see CI artifacts).

## License

GPL-3.0, see LICENSE. Upstream © Lev Gorodetskii and contributors.
