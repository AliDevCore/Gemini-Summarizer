# Gemini Summarizer 🤖📱

**Intelligent Multi-Account Android Summarization Application Powered by Cloudflare Workers AI (`@cf/google/gemma-4-26b-a4b-it`)**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Kotlin-1.9.24-purple.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/Jetpack_Compose-Material_3-orange.svg)](https://developer.android.com/jetpack/compose)

---

## 🌟 Executive Overview

**Gemini Summarizer** is a production-grade, privacy-focused native Android application built with **Kotlin** and **Jetpack Compose (Material 3)**. It is designed to intelligently ingest, parse, chunk, and summarize long texts, live web articles via URL, and complex documents (PDF, DOCX, TXT, Markdown, CSV) using Google's **Gemma 4 26B Instruct** model hosted serverless on **Cloudflare Workers AI** (`@cf/google/gemma-4-26b-a4b-it`).

### 🔑 Key Capabilities
- **⚡ Serverless Cloudflare Workers AI:** Direct integration with Cloudflare Workers AI running `@cf/google/gemma-4-26b-a4b-it` featuring a massive **256,000 token context window** and high-accuracy multilingual (including Persian and English) comprehension.
- **🔄 Multi-Account Auto-Rotation:** Add multiple Cloudflare API Tokens and Account IDs. The engine automatically balances requests and seamlessly rotates to the next available account upon rate limiting (HTTP 429).
- **🌐 Live Web Page & Article Summarization:** Simply paste any web URL (e.g. `https://...`) into the input box; the app fetches the live HTML via **Jsoup**, strips boilerplate, and delivers an executive summary.
- **📑 Document Ingestion & Local Extraction:** Built-in lightweight, pure-Kotlin parsers extract selectable text from PDF documents and Word DOCX archives (`word/document.xml`), streaming them cleanly into Gemma 4's 256K context.
- **🛑 Cancellable HTTP & Live Diagnostics:** Truly cancellable network calls via coroutines with an on-demand Stop button and live timestamped activity console.
- **🌍 Bidirectional RTL Support:** Proper per-paragraph bidirectional rendering and typography for Persian, Arabic, and multilingual outputs.
- **✨ Rich Markdown Formatting:** Bold headlines (`**bold**`), italics (`*italic*`), monospace inline code (`` `code` ``), and clean bullet points (`•`).
- **💾 Multi-Page Styled Export:** Multi-page PDF export using Android `StaticLayout` with RTL support, plus structured DOCX export with Word OpenXML headings and bullets.

### 🔄 The Core Problem Solved
Single AI API accounts frequently operate under strict rate limits and quota caps (HTTP 429). When running high-volume summarization workflows, single-account quotas are quickly exhausted.

**Gemini Summarizer solves this through an autonomous multi-account Auto-Rotation Engine:**
1. Manages a flexible pool of Cloudflare credentials (API Token + Account ID pairs).
2. Automatically routes summarization requests through the official `@cf/google/gemma-4-26b-a4b-it` model.
3. Automatically detects rate limits (`HTTP 429`) and seamlessly switches to the next configured Cloudflare account without interrupting the user.
4. Validates API Tokens and Account IDs on demand via built-in connection testing.
5. Ingests full documents in single unified passes leveraging the 256K context window.

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
│   └── engine/          # AutoRotationEngine & HierarchicalSummarizer
│
└── presentation/
    ├── home/            # Summarization screen, input editor, file picker, results
    ├── accounts/        # Cloudflare API Token & Account ID multi-account management
    ├── models/          # Cloudflare Workers AI model details & connection testing
    ├── history/         # Searchable local history of past summaries
    └── settings/        # Global defaults, language selection, security wipe
```

---

## 🔄 Auto-Rotation Engine Workflow

The rotation algorithm strictly adheres to high-availability principles:

```
[Start Summarization]
         │
         ▼
[Select Target Model: @cf/google/gemma-4-26b-a4b-it]
         │
         ▼
[Select Next Active Cloudflare Account (Token + Account ID)]
         │
         ▼
[Check Account Quota Cooldown] ──(Cooldown Active)──► [Skip to Next Account]
         │
    (Available)
         ▼
[Execute Cloudflare Workers AI Run Request]
         │
 ┌───────┴───────────────────────┬────────────────────────┬──────────────────────┐
 │ (HTTP 200 OK)                 │ (HTTP 429 Rate Limit)  │ (HTTP 401/403/404)   │ (HTTP 5xx / Network)
 ▼                               ▼                        ▼                      ▼
[Return Success]        [Set Cooldown for Acc]       [Mark Key Invalid]     [Exponential Retry x2]
                                 │                        │                      │
                                 ▼                        ▼                      ▼
                        [Try Next Account]           [Try Next Key]         [Try Next Option]
```

---

## 📄 Ingestion & Export Capabilities

### Ingestion Support (Files, Text & Live Web)
- **Web Pages & Online Articles:** Direct URL processing (`https://...`). The app fetches the article content and strips boilerplate before summarization.
- **PDF Documents:** Native pure-Kotlin text stream parsing with automatic FlateDecode decompression, extracting selectable text without heavy external libraries.
- **Word Documents (DOCX):** Parses standard OpenXML archives (`word/document.xml`) for paragraphs and formatting.
- **Plain Text & Markdown:** UTF-8 support for TXT, MD, CSV, HTML, and JSON.
- **Direct Text Input:** Manual pasting and typing with real-time word and character counters.

### Export Capabilities
Export summaries directly to your device via Android's Storage Access Framework:
- **PDF:** Formatted document with titles, generation metadata, and custom layout.
- **DOCX:** Native Microsoft Word document with headers and styling.
- **TXT & Markdown:** Clean text files with metadata blocks.

---

## 🔐 Security & Privacy Architecture

- **Android Keystore Encryption:** API tokens are encrypted at rest using AES-256-GCM backed by hardware-backed Keystore (`MasterKey`).
- **No Shared Keys / Zero Telemetry:** No hardcoded keys exist in source code, logs, or crash reports.
- **Masked Presentation:** Tokens are displayed in masked format in the UI.
- **Direct HTTPS Communication:** All requests are sent directly between the Android client and `api.cloudflare.com` over TLS.

---

## 🚀 Automated CI/CD & GitHub Releases

The repository contains a fully automated GitHub Actions pipeline (`.github/workflows/release.yml`):
- **On every push to `main`:**
  1. Sets up JDK 17 and Android SDK.
  2. Runs automated unit tests (`./gradlew test`).
  3. Builds signed release APK package (`./gradlew assembleRelease`).
  4. Automatically generates a release tag (e.g., `v1.0.X`).
  5. Publishes a GitHub Release with the compiled `.apk` attached for direct download.

---

## 🛠️ Building From Source

### Prerequisites
- JDK 17 (Eclipse Temurin recommended)
- Android SDK 34 (handled automatically by GitHub Actions in cloud builds)

### Build Commands
```bash
# Run unit tests
./gradlew test

# Assemble debug APK
./gradlew assembleDebug

# Assemble signed release APK
./gradlew assembleRelease
```

---

## 📜 License
Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
