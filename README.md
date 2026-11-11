
# Kepler 🪐

> **Interactive science and knowledge notebooks for Android.**  
> Pair rich Markdown chapters with embedded simulations, KaTeX formulas, active recall flashcards, and open folder-backed vaults.

---

## 🧭 What is Kepler?

**Kepler** is a standalone, offline-first knowledge workspace engineered for students, engineers, researchers, and technical writers. 

Traditional note-taking apps force notes to be static text or trap your thoughts inside proprietary databases. Kepler is built on a different principle: **complex ideas are best understood through interaction**. Every notebook in Kepler pairs structured CommonMark chapters with **live HTML5/Canvas simulations**, **display math**, and **active recall prompts**—all stored directly as ordinary files and directories on your device.

No account. No cloud dependency. No data lock-in.

---

## ✨ Key Features

### 🌌 Visual Companions & Simulations
- **Per-Chapter Simulators:** Attach self-contained HTML5, Canvas, WebGL, or SVG simulations directly to any chapter (e.g., orbital mechanics, quantum tunneling, double-slit wave propagation).
- **Split & Fullscreen Viewports:** Toggle seamlessly between reading notes and running interactive visual models.
- **Embedded Whiteboard & Vector Canvases:** Render lightweight structured animation and whiteboard descriptors alongside content.

### ✍️ Intelligent Markdown & Math Editor
- **Live Block Composition:** Headings, blockquotes, numbered and bulleted lists, task checkboxes, and auto-closing code blocks.
- **KaTeX Formula Rendering:** First-class support for inline math (`$E = mc^2$`) and display equation blocks (`$$\dots$$`).
- **Syntax Highlighting:** Native multi-language syntax highlighting for Python, Rust, C++, Kotlin, JavaScript, SQL, Bash, PHP, and JSON.
- **Quick Notation:** Emoji shortcodes (`:rocket:`), inline timestamp references (`[04:20]`), raw markdown toggling, and multi-step undo/redo.

### 🧠 Built-In Active Recall
- **Concept Verification:** Store prompt-and-answer flashcard questions alongside your chapters inside each notebook.
- **Focused Review:** Drill key laws, formulas, and derivations without leaving your reading workspace.

### 📂 Open Vault Architecture (Zero Lock-In)
- **Ordinary Files on Disk:** A Kepler notebook is simply a directory containing `.md` files, a clean `book.json` metadata file, and optional `visuals/` and `questions.json`.
- **Interoperability:** Mount your workspace in Obsidian, VS Code, Git, Pandoc, or Syncthing. Edit on your desktop, inspect on your phone.
- **Storage Access Framework (SAF):** Pick any storage directory or SD card tree on Android.

### 🎨 Curated Bookshelf UI
- **Preset & Custom Covers:** Choose from thematic presets (*Celestial*, *Quantum*, *Solar*, *Midnight*, *Nebula*, *Crimson*) or attach custom cover illustrations.
- **Tag & Search Indexing:** Fast filtering by tags, full-text search across titles and summaries, and multiple sort modes.
- **Offline & Private:** Zero network requests, zero telemetry, zero trackers.

---

## 📁 Vault File Structure

Kepler treats any folder as a self-contained **Workspace**. Each subdirectory inside it represents a single **Book**:

```text
<Workspace_Root>/
├── Astrodynamics & Keplerian Orbits/
│   ├── book.json                     # Metadata (title, tags, cover preset, timestamps)
│   ├── cover.png                     # Optional custom cover graphic
│   ├── 01_Keplerian_Motion.md        # Chapter 1 (Standard CommonMark + KaTeX)
│   ├── 02_Vis_Viva_Equation.md       # Chapter 2
│   ├── 03_Hohmann_Transfers.md       # Chapter 3
│   ├── questions.json                # Active recall flashcards
│   └── visuals/                      # Interactive HTML companions
│       ├── 01_orbit_sim.html         # Mapped to Chapter 1 by prefix
│       └── 03_transfer_sim.html      # Mapped to Chapter 3
└── Quantum Mechanics/
    ├── book.json
    ├── 01_Wave_Particle_Duality.md
    └── visuals/
        └── 01_double_slit.html
```

### File Conventions:
- **Chapters (`01_Name.md`)**: Numbered using natural sort order (`01`, `02`, `10`). The first `# Heading` resolves as the display chapter title.
- **Visuals (`visuals/01_*.html`)**: Matched to chapter indices via numeric prefix. HTML executes in a sandboxed WebView (`allow-scripts allow-modals`) with no access to system files.
- **Questions (`questions.json`)**: An array of `id`, `title` (prompt), `answer`, and `position` entries.

---

## 🛠️ Architecture & Tech Stack

| Layer | Technologies |
|---|---|
| **Language** | Kotlin 2.0+ (Targeting JVM 17 / Android SDK 35, minSdk 26) |
| **UI Framework** | Jetpack Compose + Material 3 Dark Palette |
| **Local Cache** | Android Room Database (in-memory SQLite index of disk vaults) |
| **Filesystem Sync** | Android Storage Access Framework (`DocumentFile`) + `java.io.File` |
| **Math Engine** | KaTeX (rendered via local lightweight WebView component) |
| **Image Pipeline** | Coil with SVG decoding and Data URI support |
| **Serialization** | Google Gson |

---

## 🚀 Building & Deploying via CLI

Kepler can be developed, built, and tested completely headlessly on Linux, macOS, or Windows without opening Android Studio.

### 1. Prerequisites
- **JDK 17** (`openjdk-17-jdk` or via SDKMAN)
- **Android Command-Line Tools & SDK 35** (`platforms;android-35`, `build-tools;35.0.0`)
- **Bun** or **Node.js** (optional, for wireless deployment automation)

### 2. Build APKs

```bash
# Debug build
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Optimized release build (R8 minified)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### 3. Run Automated Tests

```bash
./gradlew testDebugUnitTest
```

### 4. Deploy to Device

#### Automated Wireless Deploy (Recommended)
Using the included deployment script:

```bash
# Debug install over Wi-Fi/ADB:
bun run deploy
# or: node scripts/wireless-deploy.js

# Optimized release install:
bun run deploy:release

# Fast reinstall (skips recompilation if APK is up to date):
bun run install:device
```

#### Manual ADB Install (USB or Wi-Fi)

```bash
adb install -r -d app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.johnesleyer.esper/com.esper.android.MainActivity
```

---

## 📄 License

Distributed under the MIT License. See [LICENSE](LICENSE) for details.
