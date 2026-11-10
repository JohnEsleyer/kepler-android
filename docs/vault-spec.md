# Esper Vault Specification — Folder Format & Interoperability

**Format version:** 3.0  
**Applies to:** Esper Android 3.0.0 (pure offline)

Esper is a local-first workspace for books, structured notes, and companion
visuals. There is no cloud backend and no proprietary database format. A
**vault** is an ordinary filesystem folder. Everything Esper displays can be
read and edited with a text editor, Obsidian, VS Code, Git, Pandoc, or a shell
script.

Room (SQLite) is used only as a disposable index/cache for fast search and
Compose state. Deleting Esper's database never deletes a book; the next scan
rebuilds the index from the folder.

---

## 1. Vault directory layout

```text
MyVault/                              # any folder you choose
├── .esper/                           # optional app state (safe to ignore)
│   ├── config.json                   # vault format/version/theme
│   └── shelves.json                  # reserved for bookshelf grouping
│
├── Orbital Mechanics/                # a book folder (folder name = fallback title)
│   ├── book.json                     # book metadata (required for a stable id)
│   ├── 01_Keplerian Orbits.md        # chapter 1
│   ├── 02_Vis-Viva Energy.md         # chapter 2
│   ├── 02.1 Derivation.md            # optional subchapter (ordering only)
│   ├── questions.json                # review questions / flashcards
│   └── visuals/                      # companion HTML/JSON files
│       ├── 01_orbit_simulation.html  # 01_ prefix attaches it to chapter 1
│       └── 02_freehand_sketch.json   # whiteboard/vector drawing
│
└── Personal Journal/                 # journal is simply another book
    ├── book.json
    ├── 2026-10-20.md
    └── 2026-10-21.md
```

### Rules Esper follows when scanning

- A direct child **directory** of the vault root is a book.
- Directories whose names start with `.` are ignored.
- Directly inside a book directory, every `*.md` file is a chapter.
- Chapters are sorted with a **natural, case-insensitive** comparison. Thus
  `2_x.md` sorts before `10_x.md`, and `02.1` sorts before `02.2`.
- Chapters without a numeric prefix still work; they are sorted by name after
  numbered files.
- `visuals/` contains companion files. `.html`/`.htm` files are rendered as
  sandboxed simulations; `.json` files are opened by the whiteboard renderer.
- Esper never writes outside the selected vault root.

The on-device default vault lives at:

```text
<Android app external files dir>/EsperVault/
```

You can instead point Esper at any folder through Android's system folder
picker (Storage Access Framework). Esper requests persistent read/write
permission and edits that folder in place.

---

## 2. Book metadata — `book.json`

`book.json` is optional when reading but is created automatically when missing.
Keep the `id` stable if you rename the folder or title.

```json
{
  "id": "book-orbital-mechanics",
  "title": "Orbital Mechanics & Trajectory Design",
  "description": "Keplerian motion, perturbation analysis, and transfer burns.",
  "coverPreset": "solar",
  "coverImage": null,
  "tags": ["Astrodynamics", "Physics", "Drafts"],
  "isPinned": true,
  "createdAt": 1771488000000,
  "updatedAt": 1771491600000
}
```

| Field | Meaning |
| --- | --- |
| `id` | Stable book identifier. If absent, Esper derives a deterministic id from the folder name. |
| `title` | Display title. Defaults to the folder name. Renaming the title does **not** rename the folder. |
| `description` | Subtitle/summary shown on the shelf. |
| `coverPreset` | Built-in gradient theme: `celestial`, `quantum`, `solar`, `midnight`, `nebula`, `crimson`. |
| `coverImage` | Optional data URI or SVG artwork. `null` means use the preset. |
| `tags` | Array of strings. Used by tag filters and the bookshelf. A `Journal` tag or title hides the book from the main shelf and exposes it in the Journal screen. |
| `isPinned` | Bookmark flag. |
| `createdAt` / `updatedAt` | Epoch milliseconds. If missing, the folder timestamp is used when available. |

Any extra keys you add are preserved when Esper updates the file.

---

## 3. Chapters — `*.md`

Chapters are plain UTF-8 Markdown (CommonMark + GFM extensions). The Esper
editor writes the exact text back to the same file, so external edits are
picked up on the next scan.

### 3.1 Naming and ordering

Recommended:

```text
01_Introduction.md
02_Theoretical Basis.md
02.1_Derivation.md
03_Results.md
```

- Leading numbers control order.
- `_` and spaces are both fine.
- Avoid `/ \ : * ? " < > |` in names (Esper replaces them with `_` on create).
- The file name is not shown as the chapter title when a Markdown heading is
  present; the heading wins.

### 3.2 Optional YAML frontmatter

Supported keys include `title`, `position`, `parent`, and arbitrary tags:

```markdown
---
title: "Keplerian Orbits"
position: 1
tags:
  - physics
  - orbits
---

# Keplerian Orbits

An orbit is the gravitationally curved trajectory of an object…
```

If frontmatter is absent, Esper uses the first non-empty Markdown line and
strips leading `#`, `>`, `-`, `*`, and backticks. This keeps the chapter list
readable without requiring frontmatter.

### 3.3 Supported content

- **Math:** inline `$E = mc^2$` and display `$$\int_0^\infty e^{-x^2}dx$$`
  (KaTeX-compatible).
- **Code fences:** ` ```kotlin `, ` ```python `, etc. with syntax highlighting.
- **Tasks:** `- [ ]` / `- [x]`.
- **Tables:** standard GFM tables and alignment.
- **Timestamp markers:** `[04:20]`; when a visual is open, Esper calls
  `window.seekTo(seconds)` in the simulation if it exists.
- **Links/images:** ordinary Markdown. Relative paths resolve inside the book
  folder when a renderer supports them.

---

## 4. Review questions — `questions.json`

A JSON array sorted by `position`:

```json
[
  {
    "id": "q-vis-viva",
    "title": "State the Vis-Viva equation and define each variable.",
    "answer": "$$v^2 = \\mu \\left(\\frac{2}{r} - \\frac{1}{a}\\right)$$ where $\\mu = GM$.",
    "position": 0
  }
]
```

- `id` is optional when importing; Esper generates one if missing.
- `title` is the prompt shown in the Questions drawer.
- `answer` is Markdown and may contain math/code.
- Esper rewrites this file whenever you add, edit, or delete a question in the
  app, preserving the canonical `id`/`title`/`answer`/`position` fields.

---

## 5. Companion visuals — `visuals/`

Each file is standalone and offline:

| Extension | Rendered as | Notes |
| --- | --- | --- |
| `.html`, `.htm` | Sandboxed WebView simulation | Use inline CSS/JS. Do not require network access. |
| `.json` | Whiteboard/vector drawing | Esper's drawing payload (elements/strokes). |

### Chapter attachment rule

A numeric prefix attaches a visual to the matching chapter (1-based):

```text
visuals/01_orbit.html   -> chapter 1
visuals/02_energy.html  -> chapter 2
visuals/03_sketch.json  -> chapter 3
```

A visual without a numeric prefix, or with a number beyond the chapter count,
falls back to the first chapter. The same numbered prefix convention is used
by the app when it creates a new visual.

An HTML visual may implement an optional seek hook:

```html
<script>
  window.seekTo = (seconds) => {
    // Called by Esper when the reader taps a [mm:ss] marker.
    player.currentTime = seconds;
  };
</script>
```

---

## 6. Optional app state — `.esper/`

`.esper/` is reserved for local UI state. It is never required to read a book,
and you can delete it safely. Esper currently writes:

- `config.json` — format version, theme, active vault name.
- `shelves.json` — reserved for shelf grouping.

Bookshelf grouping is also cached in Room. If you copy a vault to another
device, books and chapters remain complete even when `.esper/` is omitted.

---

## 7. Creating, importing, and exporting

### Create a book inside Esper

1. Tap **Create** on the bookshelf.
2. Enter the title, description, tags, cover.
3. Esper creates a folder, writes `book.json`, and creates `01_Introduction.md`.

### Open an existing folder

1. **Settings → Open external folder as vault**.
2. Pick the folder containing your book directories.
3. Grant persistent access.
4. **Re-scan folder & rebuild index**.

External edits made in Obsidian/VS Code appear after a re-scan, or whenever the
book/workspace refreshes.

### Import a package

Settings accepts:

- `.zip` containing `book.json` + chapters + `visuals/`
- `.json` single-book export

Esper unpacks the book into a new vault folder and indexes it. Existing books
are never overwritten: import creates a uniquely named folder.

### Export

**Settings → Export vault folder as ZIP** produces
`Download/esper-vault-<timestamp>.zip` containing the real folder tree
(`book.json`, Markdown, `questions.json`, `visuals/`, and `.esper/`). Unzip it
anywhere and open the folder in another tool.

---

## 8. Processing a vault with other software

### 8.1 Obsidian

1. Obsidian → **Open folder as vault**.
2. Select the Esper vault root (or a single book folder).
3. Books appear as folders; chapters are native Markdown.
4. Graph view, backlinks, search, Dataview, and Git plugins work normally.

Because Esper uses ordinary Markdown, you can edit a chapter in Obsidian and
then re-scan in Esper. Avoid changing `book.json` ids unless you intend to
create a new book identity.

### 8.2 VS Code, Foam, Dendron, Logseq

Open the folder directly. Useful extensions:

- **Markdown All in One** — TOC, shortcuts, preview.
- **Markdown+Math** / **KaTeX** preview — render `$…$`.
- **Foam** — wiki-links and graph navigation.

### 8.3 Git

```bash
cd /path/to/MyVault
git init
git add .
git commit -m "Import Esper vault"

# Later, after editing in Esper:
git status
git diff -- '*.md' 'book.json' 'questions.json'
git commit -am "Update chapters"
```

A vault is intentionally diff-friendly: one file per chapter, stable JSON
metadata, and no binary database.

### 8.4 Pandoc → PDF / EPUB / DOCX

Run inside one book folder so `book.json` and `visuals/` are not mixed into
the chapter list:

```bash
cd "MyVault/Orbital Mechanics"

# PDF via XeLaTeX (best math support)
pandoc $(ls -v *.md) \
  -o Orbital_Mechanics.pdf \
  --pdf-engine=xelatex \
  --toc \
  --number-sections

# EPUB
pandoc $(ls -v *.md) -o Orbital_Mechanics.epub --toc

# DOCX
pandoc $(ls -v *.md) -o Orbital_Mechanics.docx --toc
```

For HTML + KaTeX rendering instead of LaTeX:

```bash
pandoc $(ls -v *.md) \
  -o book.html \
  --standalone \
  --mathjax \
  --toc
```

`ls -v` sorts `2_…` before `10_…`. If your shell does not support it, list
files explicitly or use the Python helper below.

### 8.5 Static sites — Hugo, Quartz, Astro

Hugo (content directory):

```bash
mkdir -p site/content/book
cp "MyVault/Orbital Mechanics"/*.md site/content/book/
cd site
hugo new site . --force        # if not created yet
hugo server -D
```

Quartz:

```bash
npx quartz create -d /path/to/MyVault
npx quartz build
```

Astro Starlight: copy the book's `.md` files into `src/content/docs/` and keep
the same heading hierarchy. Math is rendered by `remark-math` +
`rehype-katex`.

For HTML simulations, copy `visuals/*.html` into the site's `public/` folder
and embed them with:

```markdown
<iframe src="/visuals/01_orbit.html" style="width:100%;height:480px;border:0"></iframe>
```

### 8.6 Quarto / Jupyter Book

Quarto can treat the chapter Markdown as source directly:

```bash
quarto render "MyVault/Orbital Mechanics" --to html
```

Jupyter Book: create `_toc.yml` listing chapters in numeric order, then:

```bash
jupyter-book build .
```

### 8.7 Bulk processing example (Python)

The following script prints the chapters of every book in reading order and
converts each one to standalone HTML with Pandoc:

```python
from pathlib import Path
import re
import subprocess

VAULT = Path("/path/to/MyVault")
OUT = Path("/tmp/esper-html")
OUT.mkdir(exist_ok=True)

def natural_key(path: Path):
    return [int(p) if p.isdigit() else p.lower()
            for p in re.split(r"(\d+)", path.name)]

for book in sorted(p for p in VAULT.iterdir() if p.is_dir() and not p.name.startswith(".")):
    chapters = sorted((p for p in book.glob("*.md")), key=natural_key)
    print(f"\n# {book.name}")
    for chapter in chapters:
        print(" -", chapter.name)
        target = OUT / f"{book.name}__{chapter.stem}.html"
        subprocess.run([
            "pandoc", str(chapter),
            "-o", str(target),
            "--standalone", "--mathjax", "--toc",
        ], check=True)
```

### 8.8 Converting `questions.json` to Anki

Anki imports tab-separated text. Generate a TSV per book:

```bash
python3 - <<'PY'
import json, csv
from pathlib import Path

for book in Path("/path/to/MyVault").iterdir():
    q = book / "questions.json"
    if not q.exists():
        continue
    rows = json.loads(q.read_text())
    out = book / f"{book.name}.anki.tsv"
    with out.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, delimiter="\t")
        for item in sorted(rows, key=lambda r: r.get("position", 0)):
            w.writerow([item.get("title", ""), item.get("answer", "")])
    print("wrote", out)
PY
```

Then Anki → **File → Import** → select the TSV, map field 1 to Front and
field 2 to Back.

---

## 9. Compatibility checklist

- UTF-8 encoding, LF or CRLF both accepted.
- Markdown: CommonMark + GFM tables/tasks/strikethrough.
- Math: KaTeX-compatible `$…$` and `$$…$$`.
- Do not add a second `book.json` inside subfolders; only the book root is read.
- Do not put chapter `.md` files inside `visuals/` — they are ignored there.
- Keep visual assets inside `visuals/` (or a subfolder of the book) if you want
  the whole book to stay portable as one directory.
- Relative links between chapters should use file names, e.g.
  `[Next](02_Vis-Viva%20Energy.md)`.
- If you copy a vault while Esper is running, re-scan after the copy finishes.

---

## 10. Minimal example to create by hand

```bash
mkdir -p "MyVault/Orbital Mechanics/visuals"
cd "MyVault/Orbital Mechanics"

cat > book.json <<'JSON'
{
  "id": "book-orbital-mechanics",
  "title": "Orbital Mechanics",
  "description": "A hand-made Esper book",
  "coverPreset": "solar",
  "tags": ["Physics"],
  "isPinned": false,
  "createdAt": 1771488000000,
  "updatedAt": 1771488000000
}
JSON

cat > "01_Introduction.md" <<'MD'
# Introduction

Orbital mechanics is the study of motion under gravity.

$$ v = \sqrt{\mu \left(\frac{2}{r} - \frac{1}{a}\right)} $$
MD

cat > questions.json <<'JSON'
[
  {
    "id": "q-1",
    "title": "What is the Vis-Viva equation?",
    "answer": "$$v^2 = \\mu(2/r - 1/a)$$",
    "position": 0
  }
]
JSON

cat > visuals/01_orbit.html <<'HTML'
<!doctype html>
<html>
<body style="margin:0;background:#020617;color:#818cf8;font-family:monospace">
  <canvas id="c" style="width:100vw;height:100vh"></canvas>
  <script>
    const c = document.getElementById('c');
    const x = c.getContext('2d');
    c.width = innerWidth; c.height = innerHeight;
    x.strokeStyle = '#818cf8';
    x.beginPath();
    x.arc(c.width/2, c.height/2, Math.min(c.width,c.height)/3, 0, Math.PI*2);
    x.stroke();
  </script>
</body>
</html>
HTML
```

Open the vault in Esper, scan, and the book appears with one chapter and one
attached simulation.
