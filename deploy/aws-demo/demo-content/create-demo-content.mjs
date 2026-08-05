// SPDX-License-Identifier: AGPL-3.0-only
//
// Stages the demo reviews through the public API (issue #710): uploads the
// PDFs produced by generate-pdfs.mjs, invites the cast, starts the workflow
// and plays back the scripted, fully anchored discussion (annotations,
// replies, resolutions, finalization) from screenplay.mjs.
//
//   QNOP_BASE_URL=https://demo.qnop.io node create-demo-content.mjs
//
// Idempotent per story: a taken slug means the story is already staged and
// is skipped. Sign-ins are paced to stay under the per-IP login rate limit.
import fs from 'node:fs';
import path from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import { STORIES, USERS, TEAMS } from './screenplay.mjs';

const DIR = path.dirname(new URL(import.meta.url).pathname);
const BASE = (process.env.QNOP_BASE_URL ?? 'https://demo.qnop.io') + '/api/v1';
const SEED_PASSWORD = 'Test-Pass-1234!';
const LOGIN_WINDOW_MS = 65_000;
const LOGIN_BUDGET = 8; // stay under the 10/60s per-IP auth rate limit

const tokens = new Map();
const loginTimes = [];

async function token(username) {
  if (tokens.has(username)) return tokens.get(username);
  while (loginTimes.filter((t) => Date.now() - t < LOGIN_WINDOW_MS).length >= LOGIN_BUDGET) {
    await sleep(5_000);
  }
  loginTimes.push(Date.now());
  const res = await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ usernameOrEmail: username, password: SEED_PASSWORD }),
  });
  if (res.status === 429) {
    await sleep(30_000);
    return token(username);
  }
  if (!res.ok) throw new Error(`login ${username} → ${res.status}`);
  const t = (await res.json()).accessToken;
  tokens.set(username, t);
  return t;
}

async function api(username, method, apiPath, body) {
  const res = await fetch(`${BASE}${apiPath}`, {
    method,
    headers: {
      Authorization: `Bearer ${await token(username)}`,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    throw new Error(`${method} ${apiPath} as ${username} → ${res.status}: ${(await res.text()).slice(0, 200)}`);
  }
  return res.status === 204 ? null : res.json();
}

async function upload(story) {
  const form = new FormData();
  form.append('title', story.title);
  form.append('slug', story.slug);
  if (story.dueInDays !== undefined) {
    form.append('dueAt', new Date(Date.now() + story.dueInDays * 86_400_000).toISOString());
  }
  const pdf = fs.readFileSync(path.join(DIR, 'pdfs', `${story.slug}.pdf`));
  form.append('file', new Blob([pdf], { type: 'application/pdf' }), `${story.slug}.pdf`);
  const res = await fetch(`${BASE}/documents`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${await token(story.owner)}` },
    body: form,
  });
  if (res.status === 409) return null; // slug taken — already staged
  if (!res.ok) throw new Error(`upload ${story.slug} → ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return (await res.json()).documentId;
}

async function waitExtraction(owner, docId) {
  for (let i = 0; i < 60; i += 1) {
    const { versions } = await api(owner, 'GET', `/documents/${docId}/versions`);
    const status = versions[0]?.extractionStatus;
    if (status === 'READY') return;
    if (status === 'FAILED') throw new Error(`extraction failed for ${docId}`);
    await sleep(3_000);
  }
  throw new Error(`extraction timeout for ${docId}`);
}

for (const story of STORIES) {
  const docId = await upload(story);
  if (!docId) {
    console.log(`${story.slug}: slug taken — skipping (already staged)`);
    continue;
  }
  await waitExtraction(story.owner, docId);

  for (const team of story.teams) {
    await api(story.owner, 'POST', `/documents/${docId}/participants`, { teamId: TEAMS[team] });
  }
  for (const user of story.users) {
    await api(story.owner, 'POST', `/documents/${docId}/participants`, { userId: USERS[user] });
  }

  if (story.state !== 'DRAFT') {
    await api(story.owner, 'POST', `/documents/${docId}/workflow`, { targetState: 'IN_REVIEW' });
  }

  const targets = JSON.parse(fs.readFileSync(path.join(DIR, 'targets', `${story.slug}.json`), 'utf8'));
  for (const a of story.annotations) {
    const target = targets[a.para];
    const created = await api(a.author, 'POST', `/documents/${docId}/annotations`, {
      versionNumber: 1,
      comment: a.comment,
      type: a.type,
      priority: a.priority,
      anchor: {
        region: { surfaceIndex: target.page, box: target.box },
        textQuote: { quote: target.quote, suffix: target.suffix },
      },
    });
    for (const reply of a.replies ?? []) {
      await api(reply.author, 'POST', `/annotations/${created.id}/comments`, { body: reply.body });
    }
    if (a.resolved) {
      await api(a.author, 'POST', `/annotations/${created.id}/resolve`, { note: a.resolved.note });
    }
  }

  if (story.state === 'FINALIZED') {
    await api(story.owner, 'POST', `/documents/${docId}/workflow`, { targetState: 'FINALIZED' });
  }
  console.log(`${story.slug}: staged (${story.annotations.length} annotations, state ${story.state})`);
}
console.log('Demo content staged.');
