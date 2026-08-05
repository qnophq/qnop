// SPDX-License-Identifier: AGPL-3.0-only
//
// Fetches the public-domain texts named in screenplay.mjs from Project
// Gutenberg, takes a short excerpt and renders it to a review-ready PDF.
// While rendering it records, per excerpt paragraph, the page index and the
// normalized bounding box — exactly what an annotation anchor needs
// (ADR-0009) — into targets/<slug>.json for create-demo-content.mjs.
//
//   node generate-pdfs.mjs          # writes pdfs/*.pdf and targets/*.json
//
// Downloads are cached in cache/ so re-runs are offline-friendly.
import fs from 'node:fs';
import path from 'node:path';
import PDFDocument from 'pdfkit';
import { STORIES } from './screenplay.mjs';

const DIR = path.dirname(new URL(import.meta.url).pathname);
const MARGIN = 72;
const EXCERPT_PARAGRAPHS = 16;
const MIN_PARAGRAPH_CHARS = 120;

for (const d of ['cache', 'pdfs', 'targets']) {
  fs.mkdirSync(path.join(DIR, d), { recursive: true });
}

async function fetchText(id) {
  const cached = path.join(DIR, 'cache', `pg${id}.txt`);
  if (fs.existsSync(cached)) return fs.readFileSync(cached, 'utf8');
  const url = `https://www.gutenberg.org/cache/epub/${id}/pg${id}.txt`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`fetch ${url} → ${res.status}`);
  const text = await res.text();
  fs.writeFileSync(cached, text);
  return text;
}

// Strip the Gutenberg boilerplate, join hard-wrapped lines, and keep the
// first substantial paragraphs as the excerpt.
function excerptParagraphs(raw, startMarker) {
  const start = raw.search(/\*\*\* ?START OF[^\n]*\*\*\*/);
  const end = raw.search(/\*\*\* ?END OF[^\n]*\*\*\*/);
  const body = raw.slice(start >= 0 ? raw.indexOf('\n', start) : 0, end >= 0 ? end : raw.length);
  const paragraphs = body
    .replace(/\r/g, '')
    .replace(/_/g, '') // Gutenberg marks italics with underscores
    .split(/\n\s*\n+/)
    .map((p) => p.replace(/\s+/g, ' ').trim())
    .filter(Boolean);
  // Start at the work's canonical opening line; fall back to the first
  // genuinely prose-sized paragraph to skip title pages and front matter.
  let from = startMarker ? paragraphs.findIndex((p) => p.includes(startMarker)) : -1;
  if (from < 0) from = paragraphs.findIndex((p) => p.length >= 250);
  return paragraphs
    .slice(Math.max(from, 0))
    .filter((p) => p.length >= MIN_PARAGRAPH_CHARS)
    .slice(0, EXCERPT_PARAGRAPHS);
}

// The quote for the text-quote anchor layer: the paragraph's first sentence,
// clamped to a robust length, plus a suffix slice for context.
function quoteOf(paragraph) {
  const sentence = paragraph.match(/^.{20,}?[.!?](?=\s|$)/)?.[0] ?? paragraph.slice(0, 140);
  const quote = sentence.length > 180 ? sentence.slice(0, 180) : sentence;
  return { quote, suffix: paragraph.slice(quote.length, quote.length + 32) };
}

function render(story, paragraphs) {
  const doc = new PDFDocument({ margin: MARGIN, bufferPages: true });
  const out = path.join(DIR, 'pdfs', `${story.slug}.pdf`);
  doc.pipe(fs.createWriteStream(out));
  const W = doc.page.width;
  const H = doc.page.height;
  const textWidth = W - 2 * MARGIN;
  let pageIndex = 0;
  doc.on('pageAdded', () => { pageIndex += 1; });

  const [title, author] = story.title.split(' — ');
  doc.font('Helvetica-Bold').fontSize(22).text(title, { width: textWidth });
  doc.moveDown(0.3);
  doc.font('Helvetica-Oblique').fontSize(12).text(`by ${author}`, { width: textWidth });
  doc.moveDown(0.3);
  doc.font('Helvetica').fontSize(9).fillColor('#666666')
    .text('Excerpt of a public-domain text — demo review content, not the full work.', { width: textWidth });
  doc.fillColor('black').moveDown(1.2);

  const targets = [];
  doc.font('Times-Roman').fontSize(11);
  for (const paragraph of paragraphs) {
    // A paragraph that would not even start on this page moves entirely.
    if (doc.y > H - MARGIN - 40) doc.addPage();
    const startPage = pageIndex;
    const y0 = doc.y;
    doc.text(paragraph, { width: textWidth, align: 'justify', lineGap: 1.5 });
    const y1 = pageIndex === startPage ? doc.y : H - MARGIN;
    targets.push({
      page: startPage,
      box: {
        x: MARGIN / W,
        y: y0 / H,
        width: textWidth / W,
        height: Math.max((y1 - y0) / H, 0.012),
      },
      ...quoteOf(paragraph),
    });
    doc.moveDown(0.8);
  }
  doc.end();
  fs.writeFileSync(
    path.join(DIR, 'targets', `${story.slug}.json`),
    JSON.stringify(targets, null, 1),
  );
  return targets.length;
}

for (const story of STORIES) {
  const paragraphs = excerptParagraphs(await fetchText(story.gutenbergId), story.startMarker);
  const maxPara = Math.max(0, ...story.annotations.map((a) => a.para));
  if (maxPara >= paragraphs.length) {
    throw new Error(`${story.slug}: annotation targets paragraph ${maxPara}, excerpt has ${paragraphs.length}`);
  }
  const count = render(story, paragraphs);
  console.log(`${story.slug}: ${count} paragraphs → pdfs/${story.slug}.pdf`);
}
