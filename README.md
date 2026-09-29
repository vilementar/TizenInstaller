# TizenInstaller

**Install Galaxy Store apps & watch faces on Samsung Tizen watches (2026)**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Windows](https://img.shields.io/badge/Platform-Windows-0078D6.svg)](release/TizenInstaller.exe)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg)](release/TizenInstaller.apk)

---

## TL;DR
**TizenInstaller** is a portable, standalone tool that installs official Galaxy Store `.tpk` and `.wgt` packages on Samsung Tizen smartwatches over Wi-Fi and Bluetooth, without dead certificate servers or `error: -27` / `-32` getting in the way. No complex SDK setup required — one click to install.

---

## Supported Devices
* **Gear S2 / Gear S3 Classic & Frontier**
* **Samsung Galaxy Watch (SM-R800 / SM-R805 / SM-R810)**
* **Galaxy Watch Active 1 & Active 2**
* **Galaxy Watch 3**

> **Tested on:** Samsung Galaxy Watch (SM-R800) running **Tizen 5.5.0.2**.  
> Since the underlying SDB toolchain and package management (`pkgcmd`) are universal across Tizen OS, this method is expected to work on all Tizen smartwatch models.

---

## Downloads & Verification

Pre-compiled ready-to-use binaries are located in the [`release/`](release/) folder:

| File | Target Platform | SHA-256 Checksum |
| :--- | :--- | :--- |
| **`release/TizenInstaller.exe`** | Windows 10 / 11 | `20B0EF0814D80D5E1F7403D8926CC2BD17A77424AD98B457D94F78EDC6C6CFEF` |
| **`release/TizenInstaller.apk`** | Android 7.0+ | `EC71A8FD4FC28ABE3F7F9BF3CA7B833240F0DC83F4A2595D561F608658960F78` |

---

## What Works and What Doesn't

- **Works:** Any app or watch face that was officially signed and released on the Galaxy Store (Spotify, Samsung apps, commercial watch faces, utilities). Their distributor signature chain is already trusted by the watch's internal root store.
- **Doesn't work:** Brand-new custom watch faces built from scratch in Galaxy Watch Studio without a valid distributor certificate (Samsung's signing server is permanently dead).
- **Auto-Activation:** Watch faces are automatically set as the active watch face on your wrist immediately after installation!

---

## How to Use (Windows PC)

### 1. Prepare the Watch
1. On your watch, navigate to **Settings → About Watch → Software**.
2. Tap **Software version** 5 times until the toast `"Developer options turned on"` appears.
3. Open the newly appeared **Developer options** menu → toggle **Debugging ON**.
4. Go to **Settings → Connections → Wi-Fi** → set to **Always ON** and connect to the **same Wi-Fi network as your PC**.
5. Check your watch's IP address (Wi-Fi → tap network name → scroll down to IP).
6. **Reboot the watch once** to ensure the SDB daemon is active.

### 2. Connect
1. Run `TizenInstaller.exe`.
2. Click **🔍 Scan LAN** or manually enter your watch's IP address and click **Connect**.
3. **Check your watch screen:** when prompted *"Allow debugging from this computer?"*, tap **OK / Checkmark**.
4. The status badge will switch to **● Connected**.

### 3. Install
1. Click **Select .tpk or .wgt watch package...** and choose your file. The icon, Package ID, and version are parsed automatically.
2. Click **🚀 INSTALL DIRECTLY (MOUNT-INSTALL BYPASS)**.
3. The app streams the package, executes the mount-install bypass, and activates the watch face on your display.

---

## How It Works (Technical Overview)

Standard `sdb install app.tpk` talks to the high-level installation daemon, which performs strict developer certificate checks and fails with `error: -27` (`CERTIFICATE_ROOT_NOT_FOUND`) or `error: -12` (`Non trusted certificate`).

TizenInstaller uploads the package into `/opt/usr/apps/tmp/` and invokes the native package manager with the mount sideload flag:
```bash
pkgcmd -i -t tpk -p /opt/usr/apps/tmp/package.tpk -w
```
The `-w` flag bypasses developer profile certificate validation by mounting the package in store sideload mode.

If needed, TizenInstaller features a **4-tier escalation fallback chain**:
1. **Mount-Install Bypass (`-w`)** [DEFAULT]
2. **Standard Install (`-i`)** [Fallback]
3. **Debug Mode (`-G`)** [Fallback]
4. **Clean Reinstall (`-u` + `-w`)** [Fallback]

---

## Android Companion App
A work-in-progress Android client is included in the [`android/`](android/) directory. It includes:
- Pure Kotlin SDB client over Wi-Fi.
- Direct **Bluetooth RFCOMM bridge** reverse-engineered from Samsung's official `sdboverbt` tool (`UUID: 39E9AE15-62E4-4529-9019-8A2C07A27051` and `b6a09fda-886e-45ad-9c36-5050db58c8ff`).
- Device discovery and pairing work, though large file transfers over Wi-Fi are currently sensitive to cellular routing on certain vendors (e.g., MIUI/HyperOS). The Windows version is recommended for general use.

---

## Development & AI Assistance
Transparency: I used AI as an assistant and force multiplier. The research direction, finding the `-w` mount-install bypass flag, reverse-engineering the SDB communication flow, and testing on hardware were done by myself. AI assisted with analyzing heavily obfuscated Java bytecode/DEX structures and assembling the modern One UI interface.

---

## License
This project is open-source under the [MIT License](LICENSE).
Tizen is a trademark of The Linux Foundation. Samsung and Galaxy are trademarks of Samsung Electronics Co., Ltd.
