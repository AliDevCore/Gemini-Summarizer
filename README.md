# Gemini Summarizer 🤖📱

**Intelligent Multi-Account Android Summarization Application Powered by Cloudflare Workers AI & Hierarchical Context Architecture**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Kotlin-1.9.24-purple.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/Jetpack_Compose-Material_3-orange.svg)](https://developer.android.com/jetpack/compose)

---

## 🌟 Executive Overview

**Gemini Summarizer** is a production-grade, privacy-focused native Android application built with **Kotlin** and **Jetpack Compose (Material 3)**. It is designed to intelligently ingest, parse, chunk, and summarize long texts, live web articles via URL, and complex documents (PDF, DOCX, TXT, Markdown, CSV) using **4 verified high-performance models** hosted serverless on **Cloudflare Workers AI**:

1. **Google Gemma 4 26B A4B IT** (`@cf/google/gemma-4-26b-a4b-it`): ⚡ Ultra-Fast (~30s), top-tier Persian fluency & cinematic phrasing.
2. **Alibaba Qwen 3.8 27B** (`@cf/qwen/qwen3.8-27b`): ⚡ High Accuracy, deep multilingual reasoning and complex document structuring.
3. **OpenAI GPT-OSS 120B** (`@cf/openai/gpt-oss-120b`): ⚡ Flagship 117B MoE Architecture, deep analytical synthesis.
4. **Meta Llama 3.3 70B Fast** (`@cf/meta/llama-3.3-70b-instruct-fp8-fast`): ⚡ Edge Accelerated FP8, rapid balanced execution.

### 🔑 Key Capabilities
- **🎙️ Cloudflare Whisper Speech-to-Text & Media Summarization:**
  - Directly transcribe and summarize spoken dialogue from audio files (`.mp3`, `.wav`, `.m4a`, `.aac`, `.ogg`, `.flac`) and video files (`.mp4`, `.mkv`, `.mov`, `.webm`, `.3gp`).
  - Direct media URL support: automatically downloads and transcribes remote audio and video links.
  - Powered by Cloudflare Workers AI `@cf/openai/whisper-large-v3-turbo` and `@cf/openai/whisper` with automatic multi-account failover and quota recovery (10,000 free daily Neurons / ~214 minutes per account).
  - Native Android audio extraction and automatic chunking for long media.
  - Live dialogue transcript preview with word counter and instant clipboard copy.
- **🧠 Context-Preserving Hierarchical Summarization:**
  - Dynamic chunking with **250-word semantic overlap** across paragraphs.
  - **Rolling context accumulation** so each section knows what came before without narrative breaks.
  - **Holistic synthesis stage** eliminating redundant points and preserving unbroken thought progression.
  - Eliminates Cloudflare Workers AI `Input sequence too long` and edge timeout errors completely.
- **🔄 Dual-Tier Auto-Rotation Engine:**
  - Automatic failover across prioritized models and configured API keys.
  - Seamlessly rotates when a model or account encounters rate limits (HTTP 429) or transient errors.
- **🎛️ AI Models Priority Management:**
  - View all 4 models with real-time connection status.
  - Reorder priority order (#1, #2, #3, #4) or toggle individual models on/off.
- **🌐 Live Web Page & Article Summarization:**
  - Paste any web URL (`https://...`); the app fetches live HTML via **Jsoup**, strips boilerplate, and delivers an executive summary.
- **📑 Document Ingestion & Local Extraction:**
  - Built-in lightweight, pure-Kotlin parsers extract selectable text from PDF documents and Word DOCX archives (`word/document.xml`).
- **🛑 Cancellable Coroutines & Live Logs:**
  - Cancellable network operations with an on-demand Stop button and live timestamped activity console.
- **🌍 Bidirectional RTL Support:**
  - Proper per-paragraph bidirectional rendering and typography for Persian, Arabic, and multilingual outputs.
- **💾 Multi-Page Styled Export:**
  - Multi-page PDF export with RTL support, plus structured Word DOCX export with headings and bullets.

---

## 🏗️ System Architecture

The application is structured according to **Clean Architecture** and **MVVM** principles with strict separation of concerns:

```
com.gemini.summarizer/
│
├── core/
│   ├── security/        # Android Keystore (AES-256-GCM) secure token storage
│   ├── network/         # Cloudflare Workers AI REST client (OkHttp) with error parsing
│   ├── database/        # Room Database (Accounts, Keys, Models, Quota, History)
│   ├── datastore/       # Preferences DataStore for configuration
│   ├── file/            # Pure-Kotlin document text extraction (PDF, DOCX, TXT, MD, CSV)
│   ├── export/          # Export generation (PDF, DOCX, TXT, Markdown)
│   └── navigation/      # Jetpack Compose Navigation & Bottom Navigation Bar
│
├── domain/
│   ├── model/           # SummaryConfig, Execution results
│   └── engine/          # AutoRotationEngine & HierarchicalSummarizer (Rolling Context)
│
└── presentation/
    ├── home/            # Summarization screen, input editor, file picker, results
    ├── accounts/        # Cloudflare API Token & Account ID multi-account management
    ├── models/          # 4 Cloudflare Workers AI models, priority reordering, connection testing
    ├── history/         # Searchable local history of past summaries
    └── settings/        # Global defaults, language selection, security wipe
```

---

## 🔄 Dual-Tier Auto-Rotation Engine

```
[Start Summarization]
         │
         ▼
[Select Highest-Priority Active Model (e.g. Gemma 4 26B)]
         │
         ▼
[Select Active Cloudflare Key (Token + Account ID)]
         │
         ▼
[Check Account Quota Cooldown] ──(Cooldown Active)──► [Try Next Key]
         │
     (Available)
         ▼
[Execute Cloudflare Workers AI Request]
         │
 ┌───────┴───────────────────────┬────────────────────────┬──────────────────────┐
 │ (HTTP 200 OK)                 │ (HTTP 429 Rate Limit)  │ (HTTP 401/403/404)   │ (All Keys Exhausted on Model)
 ▼                               ▼                        ▼                      ▼
[Return Success]        [Set Cooldown for Acc]       [Mark Key Invalid]     [Failover to Next Prioritized Model]
                                 │                        │                      │
                                 ▼                        ▼                      ▼
                        [Try Next Account]           [Try Next Key]         [Qwen 3.8 / GPT-OSS / Llama 3.3]
```

---

## 📄 License & Attribution

Distributed under the Apache 2.0 License. See [LICENSE](LICENSE) for more information.
