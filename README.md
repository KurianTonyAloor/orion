# 🌌 Orion: On-Device Autonomous Android AI Assistant

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%209.0%2B-green.svg)](https://developer.android.com)
[![Runtime](https://img.shields.io/badge/Runtime-llama.cpp%20GGUF-orange.svg)](https://github.com/ggerganov/llama.cpp)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-blue.svg)](https://developer.android.com/jetpack/compose)

> **Orion** is an open-source, private, on-device autonomous AI assistant for Android. It operates applications, orchestrates cross-app workflows, navigates screens via Accessibility, types, scrolls, captures vision snapshots, and speaks with you through duplex voice calls — powered entirely offline by local GGUF open-weights models (Qwen2.5, SmolLM2, Llama-3.2).

---

## 🌟 Capabilities & Features

### 🔄 Cross-App Workflow Orchestration
Orion can coordinate tasks across multiple installed applications without human intervention:
> *"Sreehari texted me about that onam poster make it and send it back to him as a pdf"*
- **Step 1 (Context)**: Opens WhatsApp, finds Sreehari's chat, and extracts message details.
- **Step 2 (Capability Match & Generation)**: Scans installed apps for AI image generators (Gemini / ChatGPT), formulates the prompt, generates the poster, and downloads it.
- **Step 3 (Format Conversion)**: Scans for PDF converters (e.g. Image to PDF / Adobe Scan), imports the recent poster, and converts it into a PDF document.
- **Step 4 (Delivery & Handoff)**: Reopens WhatsApp to Sreehari's chat, attaches the PDF, and sends it!

---

### 🧠 100% On-Device Local Models
Run open-weight models (`Qwen2.5-1.5B`, `SmolLM2-1.7B`, `Llama-3.2-1B`) completely offline on Qualcomm Snapdragon or MediaTek chips without cloud APIs or telemetry.

### 🌐 Live Hugging Face Model Hub
Search Hugging Face (`api.hf.co/models`) in real time, inspect quantization files (`Q4_K_M`, `Q5_K_M`, `Q4_0`), and download models with live progress bars.

### 📱 Autonomous Device Control
Powered by Android's `AccessibilityService` — reads UI hierarchies, converts screens into structured token-efficient maps, and performs clicks, swipes, text entry, and app navigation.

### 📞 Simulated Duplex Calling
Wakes your phone with a full-screen incoming call interface, custom ringtones, vibration, and continuous duplex speech.

### 👁️ Visual Screen & Camera Perception
Instant pixel-accurate screen captures via `AccessibilityService.takeScreenshot()` and on-demand CameraX photo snapshots.

### 🎈 Floating Bubble Overlay
Draggable on-screen HUD bubble (`SYSTEM_ALERT_WINDOW`) that allows instant voice consultation from any app.

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
                       │         Cross-App Task Orchestrator          │
                       │  - AppCapabilityRegistry (AI / Msg / PDF)    │
                       │  - Multi-Stage Workflow Coordinator          │
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

### Installation & Build

1. Clone the repository:
   ```bash
   git clone https://github.com/KurianTonyAloor/orion.git
   cd orion
   ```

2. Open in **Android Studio** and let Gradle sync dependencies.
3. Enable **USB Debugging** on your Android device.
4. Click **Run (▶)** in Android Studio.

---

## 📄 License

This project is licensed under the **Apache License 2.0** — see the [LICENSE](LICENSE) file for details.
