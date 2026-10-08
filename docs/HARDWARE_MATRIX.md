# NoteFlow AI — Hardware Support Matrix & Benchmark Reference

This document outlines device tier recommendations, empirical benchmark measurements, and hardware constraints for NoteFlow AI's on-device neural runtimes (Google LiteRT-LM, ONNX Runtime, and whisper.cpp).

---

## ⚠️ Empirical Reference Disclaimer

> **Important**: The metrics and tier recommendations below represent **empirical sample measurements** conducted under controlled laboratory conditions on specific test hardware (e.g., physical Google Pixel 6a at 22°C ambient temperature).
> 
> **These figures are diagnostic reference baselines, NOT universal guarantees or performance SLAs.**
> 
> Actual token generation speeds, TTFT (time-to-first-token), and indexing latencies will vary significantly depending on:
> - Thermal throttling and ambient temperature
> - Background OS load and battery optimization settings
> - Manufacturer SoC tuning and GPU driver revisions (OpenCL vs CPU fallback)
> - Available physical RAM and system memory fragmentation

---

## 📱 Device Tier Classification Matrix

| Tier | Representative Target Devices / SoCs | Recommended Local Model | Observed Reference Performance (Non-Guarantee) | Fallback / Behavior |
| :--- | :--- | :--- | :--- | :--- |
| **Tier 1 (Flagship)** | 8 GB+ RAM, Snapdragon 8 Gen 1+, Tensor G2/G3/G4, Dimensity 9000+ | Gemma 4 E4B (~2.4 GB) or E2B (~1.2 GB) | ~20–25 tokens/sec, TTFT < 700 ms | Full OpenCL GPU acceleration |
| **Tier 2 (Mid-Range)** | 6 GB RAM, Snapdragon 778G+, Tensor G1, Exynos 2100+ | Gemma 4 E2B (~1.2 GB) | ~15–20 tokens/sec, TTFT < 900 ms | OpenCL GPU acceleration, `largeHeap` enabled |
| **Tier 3 (Budget / Low-RAM)**| 4 GB RAM, Helio G99, Snapdragon 680 | Gemma 4 E2B or Cloud Fallback | ~6–10 tokens/sec (CPU XNNPACK) | CPU fallback; user prompted to use Cloud APIs if device encounters memory pressure |
| **Emulator** | Android Studio Emulator (x86_64, API 30+) | Gemma 4 E2B (Testing only) | ~5–8 tokens/sec | OpenCL unavailable; automatically switches to CPU XNNPACK |

---

## 📊 Empirical Benchmarks (Google Pixel 6a Reference Baseline)

The following reference baselines were captured on a physical **Google Pixel 6a** (Google Tensor G1 SoC, 6 GB RAM, Android 14) under ambient room temperatures.

| Operation | Typical Observed Latency (Pixel 6a) | Notes / Subsystem |
| :--- | :--- | :--- |
| **First-Launch Model Download (E2B)** | ~6–8 min | 1.2 GB download over 100 Mbps Wi-Fi |
| **Note Indexing (1,000 words)** | ~45–60 ms | Segmenting, FTS5 insert & Granite vector encoding |
| **Hybrid RAG Query (FTS5 + Vector + RRF)** | ~110–140 ms | Stage 1 candidate retrieval across 500+ notes |
| **Neural Cross-Encoder Reranking** | ~25–35 ms | Stage 2 GTE INT8 ONNX scoring for top 10 candidates |
| **Time-to-First-Token (TTFT) Gemma 4 E2B** | ~750–900 ms | OpenCL GPU accelerated |
| **Generation Speed (Gemma 4 E2B)** | ~18–22 tokens/sec | OpenCL GPU streaming |
| **Memory Rebuild Pipeline (100 notes)** | ~85–110 ms | Background asynchronous WorkManager job |

*Performance Regression Gate: If any future change degrades these reference latencies by >20% under identical test conditions, review memory allocations, database locks, and model threading.*

---

## ⚠️ Known Unsupported Configurations

1. **32-Bit CPU Architectures (`armeabi-v7a`, `x86`)**: Strictly unsupported. Google LiteRT-LM, ONNX Runtime, and 384-dim SIMD vector calculations mandate 64-bit platforms (`arm64-v8a` or `x86_64`).
2. **Legacy Android Versions (< Android 8.0 / API < 26)**: Unsupported due to modern Room 2.7, SQLCipher, and NDK C++17 runtime requirements.
3. **Ultra-Low RAM Devices (< 3.5 GB Physical RAM)**: Unsupported for local LLM inference. Android Low Memory Killer (LMK) will abort execution; users must use remote API providers (OpenAI/Gemini/Ollama) or plain notes.
4. **Custom / Stripped ROMs lacking `libOpenCL.so`**: GPU compute is disabled; app gracefully falls back to CPU XNNPACK.
