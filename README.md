# Streamify

**Android ADB Screen Sharing Application**

A fast and lightweight tool to mirror your Android device's screen to your computer using ADB.

<p align="center">
  <img src="https://github.com/77AXEL/Streamify/blob/main/streamify.png" alt="Streamify" width="600">
</p>

## 📦 Releases

| Version | Latest | Stable |
| ------- | ------ | ------ |
|  [1.0](https://github.com/77AXEL/Streamify/releases/tag/v1.0)  |   ✅  | ✅ |

---

### 🚀 Features

* **Real-time Screen Streaming** – Mirror your Android device’s screen directly to your PC.
* **Local TCP Server** – Streams are accessible via a simple local TCP server.
* **Lightweight** – No heavy dependencies used.
* **Minimal Latency** – Uses MJPEG/TCP streaming for smooth playback.
* **No Root Required** – Works on any Android device with USB debugging enabled.
* **Open Source** – Fully customizable for developers to extend or integrate.

---

## 🧪 How It Works

1. **Download Streamify**  
   Grab the latest release of the **Streamify desktop application** from the [Releases page](https://github.com/77AXEL/Streamify/releases).

2. **Install ADB (Android Debug Bridge)**  
   Download and install the official [Android SDK Platform Tools (ADB)](https://developer.android.com/tools/releases/platform-tools).  
   Make sure `adb` is available in your system PATH.

3. **Enable USB Debugging on Your Device**  
   - Go to **Settings > Developer Options** on your Android phone.  
   - Enable **USB Debugging**.

4. **Connect Your Device**  
   Plug your phone into your PC using a USB/Type-C cable (or any cable that supports data transfer).  
   Allow USB debugging authorization when prompted.

5. **Run Streamify on Your PC**  
   - Launch the Streamify desktop app.  
   - On first launch, it will automatically install the Streamify Android client on your device via ADB.

6. **Grant Permissions on Your Phone**  
   When the Streamify app opens on your Android device, grant the **screen sharing permission** in the pop-up dialog.

7. **Start Streaming**  
   Once permissions are granted, Streamify will begin mirroring your device.
   
---

## 📜 License

MIT License — feel free to use, modify, and share.

---

## 🤝 Contributing

PRs are welcome! You can help

---

<img src="https://img.shields.io/badge/Author-A.X.E.L-red?style=flat-square;">  <img src="https://img.shields.io/badge/Open Source-Yes-red?style=flat-square;">
