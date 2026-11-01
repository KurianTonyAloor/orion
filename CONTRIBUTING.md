# Contributing to Orion

Thank you for your interest in contributing to **Orion**, an open-source, on-device autonomous AI assistant for Android!

Whether you want to improve prompt templates, add support for new GGUF runtimes, optimize accessibility heuristics, or expand voice synthesis, your contributions are welcome.

---

## Code of Conduct

Please maintain a friendly, welcoming, and collaborative environment. Treat fellow contributors with respect.

---

## Getting Started

1. **Fork the Repository:**
   Click the **Fork** button at the top-right of [KurianTonyAloor/orion](https://github.com/KurianTonyAloor/orion).

2. **Clone your fork:**
   ```bash
   git clone https://github.com/<your-username>/orion.git
   cd orion
   ```

3. **Open in Android Studio:**
   - Open Android Studio -> Select **Open** -> Choose the project folder.
   - Ensure you have **JDK 17** configured under `File` -> `Settings` -> `Build, Execution, Deployment` -> `Build Tools` -> `Gradle`.
   - Let Gradle sync the project.

4. **Testing on a Physical Device:**
   - Enable **Developer Options** and **USB Debugging** on an Android 9+ device (Android 11+ recommended).
   - Click **Run (▶)** to install and test on your device.

---

## Submitting Pull Requests

1. Create a feature branch:
   ```bash
   git checkout -b feature/my-new-feature
   ```

2. Commit your changes with descriptive messages:
   ```bash
   git commit -m "feat: add support for quantized Gemma-2 models"
   ```

3. Push to your branch:
   ```bash
   git push origin feature/my-new-feature
   ```

4. Open a **Pull Request** against `master` or `main`. Provide a clear description of:
   - What problem your change solves.
   - How you tested it on an Android device or emulator.
   - Any relevant screenshots or logs.

---

## Areas We Welcome Contributions In

- **Model Runtimes & Acceleration:**
  - Vulkan and OpenCL acceleration for on-device GGUF models.
  - Multi-threading and memory footprint optimizations.
- **Vision & Multimodal:**
  - Integration with lightweight mobile vision models (e.g. Moondream2 / MobileVLM).
- **Accessibility & Autonomous Actions:**
  - Smarter heuristic element mapping for canvas/game UIs.
  - Improved multi-step goal planning logic.
- **Speech & Audio:**
  - Support for local Piper TTS voices and streaming offline STT via Sherpa-ONNX.
- **UI / UX:**
  - Enhancing the floating overlay HUD, chat animations, and calling UI.

---

## License

By contributing to Orion, you agree that your contributions will be licensed under the [Apache 2.0 License](LICENSE).
