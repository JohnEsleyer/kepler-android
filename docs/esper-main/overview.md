# esper-android — Pure Offline Architecture Overview

> **Status:** version 3.0.0. Esper is now a local-first, folder-backed
> knowledge workspace. All remote dependencies have been removed.
>
> Last updated: 2026-11-10.

## What changed in 3.0

- **No cloud:** Firebase Auth, Google Sign-In, Retrofit/OkHttp, sync queues,
  network monitoring, Turnstile challenges, and the `INTERNET` permission are
  gone. The app boots directly into the local bookshelf.
- **No account gate:** there is no login screen and no owner/visibility
  concept in the local UI. Books belong to the vault folder.
- **Folder is the source of truth:** `VaultManager` reads and writes standard
  Markdown, JSON, and HTML files. Room is a disposable index/cache.
- **Open or create books anywhere:** use the internal vault or pick an external
  directory through Android's Storage Access Framework. External edits appear
  after a re-scan.

## Core components

| Component | Responsibility |
| --- | --- |
| `data/vault/VaultManager.kt` | Scan, create, edit, import, and export vault books. Supports `File` and SAF `DocumentFile` roots. |
| `data/local/EsperDatabase.kt` | Room index for notebooks, chapters, visuals, questions, shelves. |
| `data/export/ZipImporter.kt` | Reads `.zip` / `.json` book packages into the vault. |
| `MainActivity.kt` | Offline navigation and UI state; every edit is persisted to disk. |
| `ui/lexical/*` | Offline WYSIWYG Markdown editor with syntax highlighting and math. |
| `ui/workspace/*` | Split-screen chapter editor, questions drawer, HTML/whiteboard visuals. |

## Storage contract

```text
Vault/
├── .esper/                  # optional local UI state
├── Book Name/
│   ├── book.json            # title, description, cover, tags, pinned
│   ├── 01_Chapter.md        # one Markdown file per chapter
│   ├── questions.json       # review prompts + answers
│   └── visuals/
│       ├── 01_sim.html      # attached to chapter 1 by numeric prefix
│       └── 02_sketch.json   # whiteboard payload
└── Another Book/
```

Full specification, interoperability recipes (Obsidian, VS Code, Git, Pandoc,
Hugo, Quartz, Astro, Anki), and hand-authoring examples live in
[`docs/vault-spec.md`](../vault-spec.md).

## Behaviour notes

- Chapter order uses natural sort (`2` before `10`).
- The first Markdown heading is the chapter title; YAML frontmatter `title:`
  overrides it.
- HTML visuals run in an offline WebView. A visual numbered `01_` is attached
  to chapter 1, `02_` to chapter 2, and so on.
- Export creates `Download/esper-vault-<timestamp>.zip` from the actual folder
  tree, not from Room.
- Deleting the Room database and re-scanning reconstructs the entire library
  from disk.

## Verification

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The app requires no network permission for normal reading, editing, math
rendering, code highlighting, whiteboards, or HTML simulations.
