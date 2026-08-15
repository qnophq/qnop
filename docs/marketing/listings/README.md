<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Directory listings — submission kits

Ready-to-paste submission material for the directories where self-hosters
discover software. Keep descriptions in sync with the README and qnop.io
when the positioning changes.

| Directory | Status | Kit |
|---|---|---|
| [awesome-selfhosted](https://github.com/awesome-selfhosted/awesome-selfhosted-data) | ⏳ **Blocked until 2026-11-19** — their rule: *"first released more than 4 months ago"* (qnop v1.0.0: 2026-07-19) | [awesome-selfhosted-qnop.yml](awesome-selfhosted-qnop.yml) |
| [AlternativeTo](https://alternativeto.net/manage-item/) | ✅ **Submitted 2026-08-16** — app page [alternativeto.net/software/qnop](https://alternativeto.net/software/qnop/) pending review; Filestage, Ziflow, PageProof and PandaDoc suggested as alternatives (GoVisually is not on AlternativeTo; Acrobat Reader skipped — no shared app type) | [alternativeto.md](alternativeto.md) |
| [selfh.st](https://selfh.st/apps/) | ✅ **Submitted** via their Project Launch form — awaiting listing | [selfhst.md](selfhst.md) |
| [SaaSHub](https://www.saashub.com/submit) | ✅ **Submitted**, ownership verified — awaiting listing | [saashub.md](saashub.md) |

Two of the four directories gate submission on elapsed time rather than on
content, so those kits sit finished until their window opens. Both waiting
periods carry the exact moment they expire; an earlier attempt only burns a
moderation slot.

## AlternativeTo — how to submit (on/after 2026-08-09, 10:32 Europe/Stockholm)

1. Sign in with the existing account — the 7-day age requirement is counted
   from account creation, so the timestamp above is when it becomes eligible,
   not when the submission is due.
2. Open <https://alternativeto.net/manage-item/> and paste the fields from
   [alternativeto.md](alternativeto.md).
3. Add the alternatives and tags from the kit; they drive most of the
   discovery traffic on that site.
4. Upload the screenshots named in the kit and submit for moderation.

## awesome-selfhosted — how to submit (on/after 2026-11-19)

1. Fork `awesome-selfhosted/awesome-selfhosted-data`.
2. Add [`awesome-selfhosted-qnop.yml`](awesome-selfhosted-qnop.yml) as `software/qnop.yml`
   (drop any comment lines; the generated fields like `stargazers_count` are
   added by their tooling — never include them).
3. Commit message: `add qnop`.
4. Open the PR and fill their checklist truthfully. One item per PR.

Their guidelines to respect (they ban LLM-spam and sloppy submissions):
no words like *free/open-source/self-hosted* in the description, sentence
case, < 250 chars, `(alternative to …)` suffix is the sanctioned way to
name competitors. The kit already carries `demo_url` — the live demo at
https://demo.qnop.io noticeably helps acceptance.
