#!/usr/bin/env bash
# Render a Markdown file (Mermaid diagrams included) to PDF.
#
#   scripts/md-to-pdf.sh [input.md] [output.pdf]
#   defaults: README.md -> target/README.pdf
#
# Pipeline: mermaid-cli renders each ```mermaid block to SVG, pandoc embeds them
# into a self-contained HTML, headless Chromium prints it to PDF. A pure Maven
# plugin can't do this — Mermaid needs a browser to draw the diagrams.
#
# Needs on PATH: pandoc, npx (Node), python3, and a Chromium. mermaid-cli fetches
# its own Chromium; reuse it or point CHROME= at any chrome/chromium binary.
set -euo pipefail

IN="${1:-README.md}"
OUT="${2:-target/$(basename "${IN%.md}").pdf}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

command -v pandoc  >/dev/null || { echo "md-to-pdf: pandoc not found"        >&2; exit 1; }
command -v npx     >/dev/null || { echo "md-to-pdf: npx (Node) not found"    >&2; exit 1; }
command -v python3 >/dev/null || { echo "md-to-pdf: python3 not found"       >&2; exit 1; }

echo '{"args":["--no-sandbox"]}' > "$WORK/pptr.json"

# 1) Render every ```mermaid block to SVG, swap in an image reference.
python3 - "$IN" "$WORK" <<'PY'
import re, sys, os, subprocess
src, work = sys.argv[1], sys.argv[2]
cfg = os.path.join(work, "pptr.json")
i = [0]
def repl(m):
    n = i[0]; i[0] += 1
    mmd = os.path.join(work, f"d{n}.mmd")
    svg = os.path.join(work, f"d{n}.svg")
    open(mmd, "w").write(m.group(1))
    subprocess.run(["npx", "--yes", "@mermaid-js/mermaid-cli",
                    "-p", cfg, "-i", mmd, "-o", svg], check=True)
    return f"![diagram]({svg})"
txt = re.sub(r"```mermaid\n(.*?)```", repl, open(src).read(), flags=re.S)
open(os.path.join(work, "doc.md"), "w").write(txt)
print(f"md-to-pdf: rendered {i[0]} diagram(s)")
PY

# 2) Markdown -> self-contained HTML (SVGs embedded as data URIs).
cat > "$WORK/style.css" <<'CSS'
@page{margin:1.1cm 1.2cm}
body{font-family:system-ui,-apple-system,sans-serif;margin:0;line-height:1.4;font-size:12.5px;color:#1a1a1a}
h1{font-size:1.7em;margin:.2em 0 .4em} h1,h2,h3{line-height:1.2}
h2{border-bottom:1px solid #ddd;padding-bottom:.15em;margin:1.1em 0 .5em;font-size:1.3em}
h3{margin:.9em 0 .4em;font-size:1.08em}
p,ul,ol{margin:.5em 0} img{max-width:100%;display:block;margin:.4em auto} a{color:#0b62c4}
pre{background:#f4f4f4;padding:.5em .7em;overflow-x:auto;border-radius:4px;font-size:11.5px}
code{font-size:11.5px} table{border-collapse:collapse;width:100%;margin:.4em 0}
td,th{border:1px solid #ccc;padding:3px 7px;font-size:11px;text-align:left;vertical-align:top} th{background:#f4f4f4}
blockquote{border-left:3px solid #ccc;margin:.5em 0;padding-left:1em;color:#555}
CSS
# Title = the file's first H1, else its basename (keeps pandoc from warning).
TITLE="$(grep -m1 '^# ' "$IN" | sed 's/^# //')"
TITLE="${TITLE:-$(basename "${IN%.md}")}"
pandoc "$WORK/doc.md" -f gfm -t html5 --standalone --embed-resources \
  -M title="$TITLE" -c "$WORK/style.css" -o "$WORK/doc.html"

# 3) HTML -> PDF via headless Chromium.
CHROME="${CHROME:-}"
[ -n "$CHROME" ] || CHROME="$(command -v chromium || command -v chromium-browser || command -v google-chrome || true)"
[ -n "$CHROME" ] || CHROME="$(ls -1 "$HOME"/.cache/puppeteer/chrome/*/chrome-linux64/chrome 2>/dev/null | head -1 || true)"
[ -n "$CHROME" ] || { echo "md-to-pdf: no Chromium found (set CHROME=/path/to/chrome)" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
"$CHROME" --headless --no-sandbox --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="$OUT" "file://$WORK/doc.html" 2>/dev/null
echo "md-to-pdf: wrote $OUT"
