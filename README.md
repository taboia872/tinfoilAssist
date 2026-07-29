# TinfoilAssist

**TinfoilAssist** is a hardened, privacy-first Android WebView wrapper designed specifically for accessing [Tinfoil Chat](https.chat.tinfoil.sh).

It is based on the privacy-focused design of `gptassist` by `@woheller69`, enhanced with hardware API hardening, domain sandboxing, and request filtering.

---

## 🛡️ Privacy & Security Features

* **Hardware & Sensor API Blocking**: Injects early JavaScript overrides to block `navigator.getBattery`, `DeviceOrientationEvent`, `DeviceMotionEvent`, `vibrate`, `NetworkInformation`, and `geolocation`.
* **Domain Whitelisting**: Intercepts all network traffic (`shouldInterceptRequest` & `shouldOverrideUrlLoading`) to strictly allow requests only to `tinfoil.sh`, `clerk.accounts.dev`, `cdn.jsdelivr.net`, and related assets.
* **WebView Telemetry Opt-Out**: Includes `<meta-data android:name="android.webkit.WebView.MetricsOptOut" android:value="true" />` to block Google WebView telemetry.
* **Isolated Data Sandboxing**: Configures `WebView.setDataDirectorySuffix("tinfoil_chat")` on Android 9+ to prevent cross-app profile sharing.
* **Linux Desktop User-Agent**: Spoofs `Mozilla/5.0 (X11; Linux ...)` to avoid mobile fingerprinting.
* **No Third-Party Trackers**: 0 analytics, 0 SDKs, 0 ads.

---

## 🛠️ Building

Open this project in **Android Studio** or build from the command line:

```bash
./gradlew assembleRelease
```

---

## 📜 License

GNU General Public License v3.0 (GPL-3.0)
