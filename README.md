# 🌌 Orion: On-Device Autonomous Android AI Assistant

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%209.0%2B-green.svg)](https://developer.android.com)
[![Runtime](https://img.shields.io/badge/Runtime-llama.cpp%20GGUF-orange.svg)](https://github.com/ggerganov/llama.cpp)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-blue.svg)](https://developer.android.com/jetpack/compose)

> **Orion** is an open-source, private, on-device autonomous AI assistant for Android. It operates applications, navigates your screen, types, scrolls, captures vision snapshots, and speaks with you through duplex voice calls — powered entirely offline by local GGUF open-weights models (Qwen2.5, SmolLM2, Llama-3.2).

---

## 🌟 Highlights & Capabilities

- 🧠 **100% On-Device Local Models**: Run open-weight models (`Qwen2.5-1.5B`, `SmolLM2-1.7B`, `Llama-3.2-1B`) completely offline on Qualcomm Snapdragon or MediaTek chips without cloud APIs or telemetry.
- 🌐 **Live Hugging Face Model Hub**: Search Hugging Face (`api.hf.co/models`) in real time, inspect quantization files (`Q4_K_M`, `Q5_K_M`, `Q4_0`), and download models with live progress bars.
- 📱 **Autonomous Device Control**: Powered by Android's `AccessibilityService` — reads UI hierarchies, converts screens into structured token-efficient maps, and performs clicks, swipes, text entry, and app navigation.
- 📞 **Simulated Duplex Calling**: Wakes your phone with a full-screen incoming call interface, custom ringtones, vibration, and continuous duplex speech.
- 👁️ **Visual Screen & Camera Perception**: Instant pixel-accurate screen captures via `AccessibilityService.takeScreenshot()` and on-demand CameraX photo snapshots.
- 🎈 **Floating Bubble Overlay**: Draggable on-screen HUD bubble (`SYSTEM_ALERT_WINDOW`) that allows instant voice consultation from any app.
- ⚡ **Hybrid Decision Engine**: Deterministic fast-path for instant device commands (`open youtube`, `go home`, `scroll up`), and cognitive LLM reasoning for conversational, visual, and multi-step tasks.

---

## 🏗️ Architecture

```
                       ┌──────────────────────────────────────────────┐
                       │               User Interaction               │
                       │  - Jetpack Compose Chat & Voice Dashboard    │
                       │  - Full-Screen Incoming Duplex Call UI       │
                       │  - Draggable Floating Assistant Bubble       │
                       └──────────────────────┬───────────────────────┘
                                              │
                                              ▼
                       ┌──────────────────────────────────────────────┐
                       │         Live Hugging Face Model Hub          │
                       │  - Real-time HF Search & Variant Inspector   │
                       │  - Download Manager & In-Memory GGUF Cache   │
                       └──────────────────────┬───────────────────────┘
                                              │
                                              ▼
                       ┌──────────────────────────────────────────────┐
                       │          Hybrid Autonomous Engine            │
                       │  - Fast-Path (0ms latency for device actions)│
                       │  - Cognitive LLM Path (llama.cpp GGUF / NDK) │
                       └──────────────────────┬───────────────────────┘
                                              │
                      ┌───────────────────────┴───────────────────────┐
                      ▼                                               ▼
         ┌───────────────────────────┐                 ┌───────────────────────────┐
         │Android AccessibilityEngine│                 │   Sensory & Vision Core   │
         │ - Semantic Screen Parser  │                 │ - ScreenCaptureManager    │
         │ - Automated Clicks & Taps │                 │ - CameraVisionManager     │
         │ - Swipes, Scrolling, Text │                 │ - VoiceEngine (STT & TTS) │
         │ - Native App Launcher     │                 │ - Audio I/O Stream Loops  │
         └───────────────────────────┘                 └───────────────────────────┘
```

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio** (Koala / Ladybug or newer)
- **JDK 17**
- An Android device running **Android 9.0 (API 28)** or higher.
  - *Recommended hardware:* Qualcomm Snapdragon 695 / 778G / 8 Gen 1+ or MediaTek Dimensity with 6GB+ RAM.

### Installation & Build

1. Clone the repository:
   ```bash
   git clone https://github.com/KurianTonyAloor/orion.git
   cd orion
   ```

2. Open the project in **Android Studio**:
   - Go to `File` -> `Open` and select the `orion` folder.
   - Let Gradle sync the dependencies.

3. Enable **USB Debugging** on your Android device:
   - Go to `Settings` -> `About Phone` -> tap **Build Number** 7 times.
   - Go to `Developer Options` -> toggle **USB Debugging** `ON`.

4. Click **Run (▶)** in Android Studio.

---

## ⚙️ Initial Device Configuration

When launching Orion for the first time:

1. **Grant Microphone & Notifications:** Tap **Allow** when prompted.
2. **Enable Device Control (Accessibility):**
   - Tap **Enable** on the top status card (or go to `Settings` -> `Accessibility` -> `Downloaded apps` -> **Orion**).
   - Toggle **ON** and tap **Allow**.
   - *(On Android 13/14 sideloaded APKs: If greyed out, tap the 3 dots in App Info -> "Allow restricted settings" first).*
3. **Download Your Model:**
   - Tap the **Brain Icon** in the top-right corner to open the **Hugging Face Model Hub**.
   - Choose a recommended model (e.g., `Qwen 2.5 1.5B` or `SmolLM2 1.7B`) or search for any GGUF model.
   - Tap **Get** to download the quantized file into local storage.

---

## 🛠️ Project Structure

```
orion/
├── app/
│   ├── src/main/java/com/orion/assistant/
│   │   ├── agent/                 # Autonomous goal planner, prompt aggregator & router
│   │   ├── audio/                 # Low-latency PCM AudioRecorder & AudioPlayer
│   │   ├── llm/                   # Hugging Face API client, download manager & llama.cpp runtime
│   │   ├── model/                 # ActionCommand, ActionResult data models
│   │   ├── service/
│   │   │   ├── accessibility/     # AccessibilityService & screen semantic parser
│   │   │   ├── call/              # Duplex call manager & notification triggers
│   │   │   ├── overlay/           # Floating assistant bubble overlay service
│   │   │   └── telecom/           # Native Android Telecom ConnectionService
│   │   ├── ui/
│   │   │   ├── call/              # Full-screen incoming call Composable UI
│   │   │   ├── chat/              # Jetpack Compose chat screen & ViewModel
│   │   │   └── model/             # Live Hugging Face search dialog & downloader
│   │   ├── vision/                # ScreenCaptureManager & CameraVisionManager
│   │   └── voice/                 # SpeechRecognizer & TextToSpeech engine
│   └── build.gradle.kts           # App-level dependencies & build rules
├── gradle/
│   └── libs.versions.toml         # Version catalog (AGP, Compose, CameraX, Coroutines)
├── LICENSE                        # Apache 2.0 Open Source License
├── CONTRIBUTING.md                # Contribution guidelines
└── README.md
```

---

## 🗺️ Roadmap & What's Next

- [x] On-Device GGUF model downloader via live Hugging Face API
- [x] Android Accessibility Screen Tree parsing & coordinate gesture fallbacks
- [x] Full-screen incoming duplex voice calls
- [x] On-demand screen and camera snapshots
- [x] Floating draggable assistant bubble HUD
- [ ] Vulkan / OpenCL GPU acceleration for GGUF models on mobile Adreno/Mali
- [ ] Multimodal vision-language models (e.g. Moondream2 / MobileVLM)
- [ ] Offline local Piper TTS neural voices
- [ ] Offline streaming ASR via Sherpa-ONNX Zipformer

---

## 🤝 Contributing

Contributions from the open-source community are warmly welcomed! Please read [CONTRIBUTING.md](CONTRIBUTING.md) to get started with pull requests and development setups.

---

## 📄 License

This project is licensed under the **Apache License 2.0** — see the [LICENSE](LICENSE) file for details.
