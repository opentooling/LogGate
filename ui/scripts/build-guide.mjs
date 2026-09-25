// Builds the public guide from docs/USER-GUIDE.md, so the guide people read
// in LogGate and the one in the repository are the same document and cannot
// drift apart. Writes public/guide/, which Vite copies into the build as is.
import { copyFileSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Marked } from "marked";

const here = dirname(fileURLToPath(import.meta.url));
const docs = join(here, "..", "..", "docs");
const out = join(here, "..", "public", "guide");
const REPO = "https://github.com/opentooling/LogGate/blob/main";

/** GitHub's heading anchors, so links written for GitHub work here too. */
export function slug(text) {
  return text
    .toLowerCase()
    .trim()
    .replace(/<[^>]+>/g, "")
    .replace(/[^\p{L}\p{N}\s-]/gu, "")
    .replace(/\s/g, "-");
}

const escapeHtml = (s) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** The guide as a page: the document, and a table of contents of its sections. */
export function render(markdown) {
  // The document's own contents list gives way to the sidebar, which stays in
  // view while the document scrolls.
  const body = markdown
    .replace(/^## Contents\n[\s\S]*?(?=^## )/m, "")
    // Absolute, so they resolve whether the page is /guide or /guide/.
    .replace(/(src|srcset)="images\//g, '$1="/guide/images/')
    .replace(/\]\(images\//g, "](/guide/images/");
  const sections = [];
  const marked = new Marked({
    gfm: true,
    renderer: {
      heading({ tokens, depth, text }) {
        const id = slug(text);
        if (depth === 2) sections.push({ id, text });
        return `<h${depth} id="${id}">${this.parser.parseInline(tokens)}</h${depth}>\n`;
      },
      link({ href, title, tokens }) {
        // Links into the rest of the repository go to it on GitHub; the page
        // has no copy of it.
        const target = href.startsWith("../") ? `${REPO}/${href.slice(3)}` : href;
        const external = /^https?:/.test(target);
        return `<a href="${escapeHtml(target)}"${title ? ` title="${escapeHtml(title)}"` : ""}${
          external ? ' rel="noopener"' : ""
        }>${this.parser.parseInline(tokens)}</a>`;
      },
    },
  });
  const html = marked.parse(body);
  return { html, sections };
}

function page({ html, sections }) {
  const toc = sections.map((s) => `<li><a href="#${s.id}">${escapeHtml(s.text)}</a></li>`).join("\n          ");
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Guide · LogGate</title>
    <!-- Generated from docs/USER-GUIDE.md by ui/scripts/build-guide.mjs. Edit that. -->
    <link rel="stylesheet" href="/site.css" />
    <script src="/theme-boot.js"></script>
  </head>
  <body>
    <header class="site-head">
      <a class="brand" href="/welcome"><span class="mark" aria-hidden="true">L</span>LogGate</a>
      <nav class="site-nav" aria-label="Site">
        <a href="/welcome">Home</a>
        <a class="button small primary" href="/">Open LogGate</a>
      </nav>
    </header>
    <div class="guide">
      <nav class="toc" aria-label="Sections">
        <p>On this page</p>
        <ol>
          ${toc}
        </ol>
      </nav>
      <article class="doc">
${html}
      </article>
    </div>
    <script>
      // The section being read, marked in the contents.
      const links = new Map(
        [...document.querySelectorAll(".toc a")].map((a) => [a.getAttribute("href").slice(1), a]),
      );
      const observer = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            if (!entry.isIntersecting) continue;
            links.forEach((a) => a.removeAttribute("aria-current"));
            links.get(entry.target.id)?.setAttribute("aria-current", "true");
          }
        },
        { rootMargin: "0px 0px -70% 0px" },
      );
      document.querySelectorAll(".doc h2[id]").forEach((h) => observer.observe(h));
    </script>
  </body>
</html>
`;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  rmSync(out, { recursive: true, force: true });
  mkdirSync(join(out, "images"), { recursive: true });
  writeFileSync(join(out, "index.html"), page(render(readFileSync(join(docs, "USER-GUIDE.md"), "utf8"))));
  for (const image of readdirSync(join(docs, "images"))) {
    copyFileSync(join(docs, "images", image), join(out, "images", image));
  }
  console.log(`guide built into ${out}`);
}
