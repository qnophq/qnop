<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Directory listings — submission kits

Ready-to-paste submission material for the directories where self-hosters
discover software. Keep descriptions in sync with the README and qnop.io
when the positioning changes.

| Directory | Status | Kit |
|---|---|---|
| [awesome-selfhosted](https://github.com/awesome-selfhosted/awesome-selfhosted-data) | ⏳ **Blocked until 2026-11-19** — their rule: *"first released more than 4 months ago"* (qnop v1.0.0: 2026-07-19) | [awesome-selfhosted-qnop.yml](awesome-selfhosted-qnop.yml) |
| [AlternativeTo](https://alternativeto.net/manage-item/) | Ready — needs an AlternativeTo account | [alternativeto.md](alternativeto.md) |
| [selfh.st](https://selfh.st/apps/) | Ready — submit via their apps-list GitHub repo or contact form | [selfhst.md](selfhst.md) |
| [SaaSHub](https://www.saashub.com/submit) | Ready — needs a SaaSHub account | [saashub.md](saashub.md) |

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
