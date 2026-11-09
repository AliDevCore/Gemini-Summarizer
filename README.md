# Gemini Summarizer 🤖📱

**Intelligent Multi-Account Android Summarization Application Powered by Google Gemini Developer API**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Kotlin-1.9.24-purple.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/Jetpack_Compose-Material_3-orange.svg)](https://developer.android.com/jetpack/compose)

---

## 🌟 Executive Overview

**Gemini Summarizer** is a production-grade, privacy-focused native Android application built with **Kotlin** and **Jetpack Compose (Material 3)**. It is designed to intelligently ingest, parse, chunk, and summarize long texts and complex documents (PDF, DOCX, TXT, Markdown, CSV) using Google's state-of-the-art **Gemini Developer APIs**.

### 🔑 The Core Problem Solved
Google Gemini API accounts operate under project-level rate limits (e.g., Requests Per Minute [RPM], Tokens Per Minute [TPM], Requests Per Day [RPD]). When a developer or researcher runs high-volume summarization workflows, single-account quotas are quickly exhausted (HTTP 429 / `RESOURCE_EXHAUSTED`).

**Gemini Summarizer solves this through an autonomous multi-tier Auto-Rotation Engine:**
1. Allows registering multiple independent Google Accounts.
2. Supports mapping multiple API Keys to their respective Google Cloud Project IDs.
3. Dynamically routes requests through the strongest available Gemini model across independent accounts.
4. Seamlessly rotates to alternate accounts/projects upon hitting rate limits without interrupting the user.
5. Employs hierarchical text chunking for documents that exceed standard context limits.

---

## 🏗️ System Architecture

The application is structured according to **Clean Architecture** and **MVVM** principles with strict separation of concerns:

```
com.gemini.summarizer/
│
├── core/
│   ├── security/        # Android Keystore (AES-256-GCM) secure key storage
│   ├── network/         # HTTPS OkHttp client with Gemini API error parsing
│   ├── database/        # Room Database (Accounts, Keys, Models, Quota, History)
│   ├── datastore/       # Preferences DataStore for configuration
│   ├── file/            # Document extraction (PDF, DOCX, TXT, MD, CSV)
│   ├── export/          # File generation (PDF, DOCX, TXT, Markdown)
│   └── navigation/      # Jetpack Compose Navigation & Bottom Navigation Bar
│
├── domain/
│   ├── model/           # SummaryConfig, Execution results
│   └── engine/          # AutoRotationEngine & HierarchicalSummarizer
│
└── presentation/
    ├── home/            # Summarization screen, input editor, file picker, results
    ├── accounts/        # Multi-account and multi-key management with Project IDs
    ├── models/          # Gemini model inventory, auto-priority, API discovery
    ├── history/         # Searchable local history of past summaries
    └── settings/        # Global defaults, language selection, security wipe
```

---

## 🔄 Auto-Rotation Engine Workflow

The rotation algorithm strictly adheres to capability-first principles:

```
[Start Summarization]
         │
         ▼
[Select Highest-Priority Active Model (e.g. Gemini 2.5 Pro)]
         │
         ▼
[Select Next Active Google Account (e.g. Account 1)]
         │
         ▼
[Check Project Quota Cooldown] ──(Cooldown Active)──► [Skip to Next Project/Account]
         │
    (Available)
         ▼
[Execute Gemini generateContent Request]
         │
 ┌───────┴───────────────────────┬────────────────────────┬──────────────────────┐
 │ (HTTP 200 OK)                 │ (HTTP 429 Exhausted)   │ (HTTP 401/403)       │ (HTTP 5xx / Network)
 ▼                               ▼                        ▼                      ▼
[Return Success]        [Set Cooldown for Project]   [Mark Key Invalid]     [Exponential Retry x2]
                                 │                        │                      │
                                 ▼                        ▼                      ▼
                        [Try Next Project/Acc]       [Try Next Key]       [Try Next Option]
                                 │
                         (All Accs Exhausted)
                                 │
                                 ▼
                    [Step Down to Next Model]
                    (e.g. Gemini 2.0 Flash)
```

---

## 📄 Document Ingestion & Export

### Ingestion Support (SAF)
- **PDF Documents:** Extracts text streams and page counts; automatically detects scanned/empty documents.
- **Word Documents (DOCX):** Parses standard OpenXML archives (`word/document.xml`) for paragraphs and formatting.
- **Plain Text & Markdown:** UTF-8 support for TXT, MD, CSV, HTML, and JSON.

### Export Capabilities
Export summaries directly to your device via Android's Storage Access Framework:
- **PDF:** Formatted document with titles, generation metadata, and custom layout.
- **DOCX:** Native Microsoft Word document with headers and styling.
- **TXT & Markdown:** Clean text files with metadata blocks.

---

## 🔐 Security & Privacy Architecture

- **Android Keystore Encryption:** API keys are encrypted at rest using AES-256-GCM backed by hardware-backed Keystore (`MasterKey`).
- **No Shared Keys / Zero Telemetry:** No hardcoded keys exist in source code, logs, or crash reports.
- **Masked Presentation:** Keys are displayed in masked format (`AIzaSy...****`) in the UI.
- **Zero Third-Party Servers:** All interactions occur directly between the client device and `generativelanguage.googleapis.com` via HTTPS.

---

## 🚀 Automated CI/CD & GitHub Releases

The repository contains a fully automated GitHub Actions pipeline (`.github/workflows/release.yml`):
- **On every push to `main` or tag creation (`v*`):**
  1. Sets up JDK 17 and Android SDK.
  2. Runs automated unit tests (`./gradlew test`).
  3. Builds debug and release APK packages (`./gradlew assembleDebug`).
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

# Assemble release APK
./gradlew assembleRelease
```

---

## 📜 License
Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
