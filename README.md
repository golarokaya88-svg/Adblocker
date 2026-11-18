# VoidShield

Zero-root Android local VPN DNS ad and tracker blocker with a dark privacy shield interface.

## Features

- **Zero-Root Local VPN DNS Blocker**: Intercepts outbound DNS queries (`UDP` port `53`) on-device via `VpnService` without sending browsing traffic through an external VPN server.
- **Bundled Ad & Tracker Blocklist**: Drops requests to major mobile ad networks, video ad servers, pop-up networks, and telemetry trackers with instant `NXDOMAIN` responses while forwarding legitimate lookups to `1.1.1.1` / `8.8.8.8`.
- **One-Tap Control & Live Counters**: Dark privacy shield UI with an animated toggle, persistent **Total Ads Blocked** counter, **This Session** counter, and quick pause control in the ongoing status notification.

> **Scope Note**: Because Android prohibits non-root apps from inspecting encrypted HTTPS payloads of other apps, VoidShield blocks third-party ad networks and trackers across apps and browsers. First-party ads served from the exact same domain as the content itself cannot be stripped at the DNS level without root access.

## Build & Install on Your Phone via GitHub

1. Push this repository to GitHub.
2. Open your repository on GitHub and tap the **Actions** tab.
3. Open the latest **Build VoidShield APK** workflow run (or tap **Run workflow** to trigger it manually).
4. Scroll to **Artifacts** and download **`VoidShield-debug-apk`** (extract the `.zip` to get `app-debug.apk`), or push a `v1.0.0` tag to get the direct `.apk` attached under **Releases**.
5. Tap `app-debug.apk` on your Android phone to install (allow **Install unknown apps** if prompted).
6. Open **VoidShield**, tap the shield, and approve the Android VPN connection prompt.

## Local Build

```bash
base64 -d debug.keystore.base64 > debug.keystore
gradle testDebugUnitTest assembleDebug
```
