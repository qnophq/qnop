/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.qnop.service.review.export;

/**
 * The HTML report's stylesheet and script, inlined (issue #637).
 *
 * <p>Constants rather than resource files, and that is the point: everything {@link HtmlWriter#raw}
 * accepts has to be a literal, so a user string can never take this path. It also keeps the promise
 * the format makes — one file, no fetches — visible in one place.
 *
 * <p>System fonts only. Embedding a webface would add megabytes to a document meant to travel as a
 * mail attachment, and the report's typography does not depend on one.
 *
 * <p>Light only, deliberately: this artifact is printed and filed, and a document that guesses at
 * the reader's theme guesses wrong on paper.
 */
final class HtmlReportStyle {

  private HtmlReportStyle() {}

  static final String CSS =
      """
      *,*::before,*::after{box-sizing:border-box}
      body{margin:0;background:#f4f5f7;color:#111827;
        font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif}
      .sheet{max-width:60rem;margin:0 auto;padding:2.5rem 1.5rem 5rem}
      .masthead{display:flex;flex-wrap:wrap;align-items:flex-start;
        justify-content:space-between;gap:1rem 2rem;
        padding-bottom:1.25rem;border-bottom:2px solid #111827}
      /* An operator's logo is whatever they uploaded: without a width bound a wide
         one pushes the whole page sideways on a phone. */
      .masthead img{max-height:44px;max-width:100%;width:auto}
      h1{margin:0;font-size:1.9rem;line-height:1.2;letter-spacing:-.02em}
      .sub{margin:.35rem 0 0;color:#6b7280;font-size:.875rem}
      .controls{display:flex;flex-wrap:wrap;gap:.5rem;align-items:center;margin:1.5rem 0 .5rem}
      .controls input{flex:1 1 16rem;min-width:12rem;padding:.5rem .7rem;font:inherit;
        border:1px solid #d1d5db;border-radius:.5rem;background:#fff}
      .controls button{padding:.45rem .8rem;font:inherit;font-size:.85rem;cursor:pointer;
        border:1px solid #d1d5db;border-radius:999px;background:#fff;color:#374151}
      .controls button[aria-pressed=true]{background:#1f3a8a;border-color:#1f3a8a;color:#fff}
      .count{color:#6b7280;font-size:.85rem}
      .finding{background:#fff;border:1px solid #e5e7eb;border-radius:.75rem;
        padding:1.25rem 1.5rem;margin:1rem 0;box-shadow:0 1px 2px rgba(17,24,39,.04)}
      .finding[hidden]{display:none}
      .key{display:flex;align-items:baseline;gap:.6rem;flex-wrap:wrap}
      .key h2{margin:0;font-size:1.05rem;color:#1f3a8a;letter-spacing:-.01em}
      /* Flex, because the facts are written without whitespace between them and
         inline boxes cannot break where there is none — a timestamp split across
         two lines reads as two facts, and nowrap alone would push the line out of
         the card. The separator trails its fact so a wrap never starts a line
         with a lone dot. */
      .facts{display:flex;flex-wrap:wrap;column-gap:.5rem;row-gap:.15rem;
        margin:.35rem 0 .9rem;color:#6b7280;font-size:.8125rem}
      .facts span{white-space:nowrap}
      .facts span:not(:last-child)::after{content:"·";margin-left:.5rem;color:#d1d5db}
      .body>*:first-child{margin-top:0}
      .body>*:last-child{margin-bottom:0}
      .body p{margin:.6rem 0}
      .body h3,.body h4,.body h5{margin:1rem 0 .4rem;font-size:1rem}
      .body ul,.body ol{margin:.5rem 0;padding-left:1.4rem}
      .body blockquote{margin:.7rem 0;padding-left:.9rem;border-left:2px solid #c7d2e4;color:#374151}
      .body pre{margin:.7rem 0;padding:.7rem .9rem;overflow-x:auto;background:#f8fafc;
        border:1px solid #e5e7eb;border-radius:.4rem}
      .body code,.body pre{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;
        font-size:.86em}
      .body img{max-width:100%;height:auto;border:1px solid #e5e7eb;border-radius:.4rem;margin:.6rem 0}
      .body table{border-collapse:collapse;margin:.7rem 0;font-size:.9em}
      .body th,.body td{border:1px solid #e5e7eb;padding:.35rem .6rem;text-align:left}
      .body th{background:#f8fafc}
      .file{display:inline-flex;align-items:baseline;gap:.4rem;margin:.35rem 0;
        font-size:.9rem;color:#1d4ed8}
      .file .meta{color:#6b7280;font-size:.8125rem}
      a{color:#1d4ed8}
      .thread{margin-top:1rem;border-top:1px solid #e5e7eb;padding-top:.5rem}
      .thread>summary{cursor:pointer;list-style:none;padding:.4rem 0;
        font-size:.75rem;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:#6b7280}
      .thread>summary::-webkit-details-marker{display:none}
      .thread>summary::before{content:"▸";display:inline-block;width:1em;color:#9ca3af}
      .thread[open]>summary::before{content:"▾"}
      .turn{padding:.6rem 0 .6rem 1rem;border-left:2px solid #c7d2e4}
      .turn.by-author{border-left-color:#1f3a8a;border-left-width:3px}
      .turn .who{font-size:.8125rem;font-weight:700;color:#111827}
      .turn.by-author .who{color:#1f3a8a}
      .turn .when{font-size:.8125rem;color:#6b7280;margin-left:.6rem}
      .empty{padding:3rem 0;text-align:center;color:#6b7280}
      @media print{
        body{background:#fff}
        .sheet{max-width:none;padding:0}
        .controls{display:none}
        .finding{break-inside:avoid;page-break-inside:avoid;
          border:0;border-bottom:1px solid #e5e7eb;border-radius:0;box-shadow:none;padding:1rem 0}
        .thread[open]>summary,.thread>summary{color:#6b7280}
        a{color:inherit;text-decoration:underline}
      }
      """;

  /**
   * Filtering and expand-all, and nothing else.
   *
   * <p>It reads from the DOM and writes only {@code hidden} and {@code open} — no data is
   * interpolated into it, because escaping for HTML is not escaping for JavaScript and a body
   * containing {@code </script>} would end the block early. Keeping the script free of content
   * makes that impossible rather than merely unlikely.
   *
   * <p>The report is complete without it. A mail client that refuses inline script costs the reader
   * the filter, never the findings — which is why the markup carries everything and the script only
   * hides parts of it.
   */
  static final String JS =
      """
      (function () {
        var q = document.getElementById('q');
        var count = document.getElementById('count');
        // Scoped to the bar: the findings carry data-status too, and a selector
        // that swept them up would turn any click on a card into a filter.
        var chips = Array.prototype.slice.call(
          document.querySelectorAll('.controls button[data-status]'));
        var findings = Array.prototype.slice.call(document.querySelectorAll('.finding'));
        var status = 'all';
        function apply() {
          var needle = (q.value || '').toLowerCase();
          var shown = 0;
          findings.forEach(function (el) {
            var okStatus = status === 'all' || el.getAttribute('data-status') === status;
            var okText = !needle || (el.textContent || '').toLowerCase().indexOf(needle) !== -1;
            var show = okStatus && okText;
            el.hidden = !show;
            if (show) shown++;
          });
          count.textContent = shown + ' of ' + findings.length;
        }
        q.addEventListener('input', apply);
        chips.forEach(function (chip) {
          chip.addEventListener('click', function () {
            status = chip.getAttribute('data-status');
            chips.forEach(function (other) {
              other.setAttribute('aria-pressed', String(other === chip));
            });
            apply();
          });
        });
        var toggle = document.getElementById('expand');
        if (toggle) {
          toggle.addEventListener('click', function () {
            var threads = document.querySelectorAll('details.thread');
            var open = toggle.getAttribute('aria-pressed') !== 'true';
            Array.prototype.forEach.call(threads, function (t) { t.open = open; });
            toggle.setAttribute('aria-pressed', String(open));
          });
        }
        apply();
      })();
      """;
}
