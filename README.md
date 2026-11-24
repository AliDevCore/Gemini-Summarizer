# Gemini Summarizer 🤖📱

**High-Performance Multi-Account Android Summarization & Speech-to-Text Application Powered by Cloudflare Workers AI**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Kotlin-1.9.24-purple.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/Jetpack_Compose-Material_3-orange.svg)](https://developer.android.com/jetpack/compose)

---

## 🌟 Overview

**Gemini Summarizer** is a production-grade, privacy-focused native Android application built with **Kotlin** and **Jetpack Compose (Material 3)**. Powered by serverless **Cloudflare Workers AI**, the application provides fast, accurate **Speech-to-Text (STT)** and **Context-Preserving Hierarchical Summarization** for audio files, video recordings, web links, documents, and long-form texts.

By managing multiple Cloudflare accounts with automatic quota rotation, Gemini Summarizer enables unlimited, free execution of state-of-the-art AI models without subscription fees, API lock-in, or third-party intermediary servers.

---

## 🚀 Key Features & Capabilities

### 🎙️ Audio & Video Speech-to-Text (STT) Powered by Whisper
- **Extensive Media Format Support**:
  - **Audio Formats**: `MP3`, `WAV`, `M4A`, `AAC`, `OGG`, `OPUS`, `FLAC`
  - **Video Formats**: `MP4`, `MKV`, `MOV`, `WEBM`, `3GP`
- **Dual Ingestion Methods**:
  - **Local Device Files**: Select voice memos, lecture recordings, podcasts, or video clips directly through the Android Storage Access Framework.
  - **Remote Media URLs**: Paste any direct audio or video link to automatically download, extract, and process the media stream.
- **Two Independent Execution Modes**:
  1. **Transcript Only**: Transcribes spoken dialogue into readable text, complete with word and character counters, and an instant 1-click clipboard copy button.
  2. **Transcribe & Summarize**: Transcribes audio and immediately feeds the resulting transcript into the AI summarization engine for an executive briefing.
- **Ultra-Compact Compression & Concurrent Processing**:
  - Automatically compresses long audio and video tracks into ultra-lightweight **16kHz Mono AAC (32 kbps)** (~240 KB per minute) and slices long recordings into compact **10-minute segments** (~2.4 MB each).
  - Dispatches audio segments **concurrently** across all configured active Cloudflare accounts for blazingly fast turnaround on multi-hour files.
- **Hallucination Prevention**:
  - Employs dynamic prompt suppression during multilingual decoding to eliminate repetitive hallucination loops.
- **Whisper Model Selection**:
  - Choose between `@cf/openai/whisper-large-v3-turbo` (ultra-fast and accurate) and `@cf/openai/whisper` (standard) in Settings.

---

### 🧠 Intelligent Multi-Model AI Summarization
Access four verified, high-performance models running serverless on Cloudflare Workers AI:

1. **Google Gemma 4 26B A4B IT** (`@cf/google/gemma-4-26b-a4b-it`):
   - Ultra-fast turnaround (~30s), fluent multilingual prose, and structured thematic clarity.
2. **Alibaba Qwen 3.8 27B** (`@cf/qwen/qwen3.8-27b`):
   - High analytical precision for complex technical papers, contracts, and detailed corporate reports.
3. **OpenAI GPT-OSS 120B** (`@cf/openai/gpt-oss-120b`):
   - Flagship 117B MoE (Mixture of Experts) architecture for deep reasoning, synthesis, and nuanced breakdowns.
4. **Meta Llama 3.3 70B Fast** (`@cf/meta/llama-3.3-70b-instruct-fp8-fast`):
   - Edge-accelerated FP8 model delivering rapid, balanced execution for articles and essays.

---

### 📑 Document & Web Ingestion
- **Document File Support**:
  - Native, lightweight, pure-Kotlin parsers extract text directly from `PDF`, `DOCX` (Word), `TXT`, `Markdown`, and `CSV` files.
- **Live Web Page Extraction**:
  - Enter any website or article URL (`https://...`). Built-in HTML parsing strips ads, sidebars, and navigation boilerplate to summarize only core article content.
- **Context-Preserving Hierarchical Chunking**:
  - Long texts are segmented with a 250-word semantic overlap between chunks.
  - Rolling context accumulation preserves narrative continuity and logical progression across sections.
  - Structured output includes:
    - **Executive Overview**: High-level core takeaways.
    - **Core Analysis & Key Insights**: Detailed arguments and thematic breakdowns.
    - **Evidence, Data & Findings**: Specific metrics, facts, and citations.
    - **Strategic Conclusions**: Actionable outcomes and final takeaways.

---

### 🔄 Multi-Account Rotation & Infinite Free Quota
- **Zero-Cost Operation**:
  - Cloudflare Workers AI provides 10,000 free Neurons per account daily (equivalent to ~214 minutes of Whisper transcription or hundreds of text summaries).
- **Multi-Account Failover**:
  - Store multiple Cloudflare accounts (API Token + Account ID).
  - When an account reaches its daily quota or encounters a rate limit (HTTP 429), the engine automatically rotates to the next active account without interrupting your workflow.
- **Model Priority Customization**:
  - Reorder model priority (#1 to #4) and toggle individual models on or off according to preference.
  - Built-in connection testing to verify API tokens and model availability in real time.

---

### 📝 Export Formats & History Management
- **Multi-Page Styled PDF Export**:
  - Generates paginated PDF documents with clean typography and full bidirectional text support.
- **Structured Word DOCX Export**:
  - Produces formatted Word documents with styled headings, bullets, and section divisions.
- **Markdown & Plain Text Export**:
  - Instant export to standard Markdown (`.md`) or text (`.txt`) for note-taking apps.
- **Searchable Local History**:
  - Saves all past summaries and speech transcripts locally in a Room database with instant search, review, and re-export.

---

### 🔒 Hardware-Backed Security & Privacy
- **Android Keystore Encryption**:
  - All Cloudflare API tokens and Account IDs are encrypted using **AES-256-GCM** with keys managed directly by the device's hardware security module (Keystore/TEE).
- **Direct Client-to-Cloudflare Architecture**:
  - Requests travel directly from your device to Cloudflare's secure endpoints over TLS.
  - No intermediate servers, no proxy layers, no user tracking, and no external data storage.

---

### 🎨 Modern Material 3 User Experience
- Built entirely with modern **Jetpack Compose**.
- Full support for **Dark Mode** and **Light Mode**.
- Clean bidirectional rendering for multilingual and Right-to-Left (RTL) scripts.
- Real-time progress indicators displaying extraction, download, transcription, and synthesis stages.

---

## 📱 Quick Start Guide

### 1. Transcribe or Summarize Audio/Video
1. Open the **Home** tab.
2. Tap the **Media (Audio / Video)** button to select a file from your device, or paste a direct media URL into the text field.
3. An **Attached Media** badge will display the detected file format and size.
4. Select your desired operation:
   - **Transcript Only**: Transcribes speech and displays an interactive preview card with word count and a copy button.
   - **Transcribe & Summarize**: Transcribes audio and immediately generates a comprehensive executive summary.

### 2. Configure Cloudflare Accounts
1. Navigate to the **Accounts** tab.
2. Enter your Cloudflare **Account ID** and an **API Token** created with `Workers AI: Read` permission.
3. Add multiple accounts to take advantage of automated quota rotation.

---

## 📄 License & Attribution

Distributed under the Apache 2.0 License. See [LICENSE](LICENSE) for more information.
