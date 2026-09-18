# NoWiFi Wireless Debugging

LSPosed module that enables Android **Wireless debugging** with **no WiFi and no
hotspot** — e.g. over mobile data or Tailscale. It also keeps the original hotspot
support. Fork of [Hotspot Wireless Debugging](https://github.com/droserasprout/io.drsr.hotspotadb)
(GPL-3.0, by Lev Gorodetskii).

## Download

Get the APK from [Releases](https://github.com/iflyabd/nowifi-adb/releases) —
no login needed.

## Requirements

- Android 15–16 (minSdk 33, targetSdk 36)
- Root with Magisk (26+) and **Zygisk enabled**
- LSPosed framework, legacy-Xposed-API build (tested: LSPosed v1.11.0 zygisk-release)
- No router or WiFi network needed for No-WiFi mode

Tested on: OnePlus CPH2487, Android 16, Magisk 30.7, LSPosed v1.11.0.

## Installation

1. Install LSPosed first (flash the zygisk release ZIP in Magisk → Modules,
   reboot, install the LSPosed manager APK if it isn't installed automatically).
2. Install `nowifi-adb-*.apk` normally (tap the file → Install).
3. Open the **LSPosed** manager app → **Modules** → enable
   **NoWiFi Wireless Debugging**.
4. Open its **Scope** and tick both:
   - **System Framework** (`system`)
   - **Settings** (`com.android.settings`)
5. **Reboot.** (Zygote must restart to load the module.)

How to confirm it loaded: in a root shell, check the LSPosed log —

```sh
su -c 'grep NoWifiAdb /data/adb/lspd/log/modules_*.log | head'
```

You should see `Loading legacy module dev.iflyabd.nowifiadb`,
`hooking framework` and `hooking Settings` with no `failed` lines.

## Usage — No-WiFi mode (no WiFi, no hotspot)

1. Make sure WiFi and hotspot are **OFF** (strictest case works).
2. Settings → Developer options → tap **Wireless debugging** to open its screen.
3. Turn on the new **No-WiFi mode** switch at the top
   (“Allow Wireless debugging without WiFi or hotspot”).
4. Turn on the main **Wireless debugging** switch — it now stays ON instead of
   refusing with “no network”.
5. The screen shows an `IP:port` address (e.g. your mobile-data or Tailscale IP).
6. First time only — pair: tap **Pair device with pairing code**, then run

```sh
adb pair <shown-ip>:<pairing-port>
# enter the 6-digit code when asked
```

7. Connect (from the same phone in Termux, or from a PC that can reach the IP):

```sh
adb connect <shown-ip>:<port>
# on-device, 127.0.0.1:<port> also works, e.g.: adb connect 127.0.0.1:40307
adb devices
adb shell
```

Notes:

- The port changes whenever `adbd` restarts — always read the current one from
  the Wireless Debugging screen (or `getprop service.adb.tls.port` as root).
- The mobile-data IP shown (e.g. `10.x.x.x`) is carrier-NAT: reachable from the
  phone itself, generally **not** from a PC. For PC access use your Tailscale IP
  (phone and PC on the same tailnet) or turn on the hotspot and use its IP.
- Turning **No-WiFi mode** back OFF restores completely stock behavior.

## Start on boot (optional)

The Wireless Debugging screen also has a **Start on boot** switch. When ON,
wireless debugging turns itself on after every reboot — but only if the hotspot
is up or **No-WiFi mode** is on at that moment. Otherwise boot stays clean.

> Security note: on-boot mode leaves an ADB port listening on all interfaces
> after every reboot. Anyone on a network the phone joins (hotspot guests,
> tailnet members) can attempt to connect — pairing authorization still applies,
> but turn the switch OFF when you don't need it.

## Usage — hotspot mode (inherited, no router needed)

1. Turn ON the WiFi **hotspot** (no upstream WiFi required).
2. Use the **Wireless debugging** toggle in hotspot settings or Developer options.
3. Pair once (`adb pair <hotspot-ip>:<pairing-port>`), then
   `adb connect <hotspot-ip>:<port>`.
4. Optional: enable **Fixed IP/port** on the Wireless Debugging screen to always
   use `192.168.49.1:5555` (pairing still uses the one-time dynamic port).

## Building from source

Pushes to `main` build a debug APK via GitHub Actions (see the CI run's
`debug-apk` artifact). Local build:

```sh
make build    # ./gradlew assembleDebug (needs JDK 21 + Android SDK)
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| Main toggle still says “no network” | No-WiFi mode is OFF, or module/scopes not enabled, or you skipped the reboot after enabling |
| `NoSuchMethodException … HotspotAdbModule` in LSPosed log | Wrong module build for your framework (v1.3.0 needs LSPosed 2.x/Vector API 101). Use this repo's v1.x release (legacy API) with LSPosed 1.x |
| `adb connect` fails to shown mobile IP | Normal on carrier NAT — use `127.0.0.1:<port>` on-device, or Tailscale/hotspot IP remotely |
| Empty port / toggle flips back after reboot | Re-check module + scopes in LSPosed manager after every reinstall |

## Other solutions

- [Hotspot Wireless Debugging](https://github.com/droserasprout/io.drsr.hotspotadb)
  (upstream of this fork — hotspot only, needs LSPosed 2.x for v1.3.0+)
- [Magisk-WiFiADB](https://github.com/mrh929/magisk-wifiadb) — legacy unencrypted
  `adb tcpip` on boot, Magisk only, no LSPosed needed

## License

GPL-3.0, see LICENSE. Upstream © Lev Gorodetskii and contributors.
