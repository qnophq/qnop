<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# AWS demo instance (demo.qnop.io)

Everything needed to run the public qnop demo on a single EC2 instance
(issue #710). The instance is **disposable**: all state worth keeping is
this directory plus the golden state, so it can be rebuilt from scratch
in minutes.

## Architecture

```
Internet ──▶ EC2 (eu-central-1, t4g.medium, Amazon Linux 2023)
              └─ docker compose (project: qnop-demo)
                 ├─ caddy     :80/:443 — TLS via Let's Encrypt
                 ├─ qnop      qnophq/qnop-ce:latest (not exposed)
                 ├─ postgres  :18
                 ├─ minio     object storage
                 └─ mc        (profile "tools", used by the scripts)
```

- **Golden state** (`/opt/qnop-demo/golden/`): a full `pg_dump` plus a
  mirror of the document bucket, captured once from a staged demo
  (seeded users, example reviews with annotations) by
  `build-golden-state.sh`.
- **Reset + auto-deploy**: `reset-demo.sh` runs from cron at 03:00 and
  15:00 UTC. It pulls the latest images (so releases roll out on their
  own), drops and restores the database, mirrors the bucket back and
  restarts the stack. The app restart also clears qnop's boot-time
  settings cache.

## Files

| File | Purpose |
|---|---|
| `user-data.sh` | EC2 cloud-init bootstrap: Docker + compose plugin, clones this repo, runs `install.sh` |
| `install.sh` | Idempotent host setup: `/opt/qnop-demo`, secrets in `.env` (first run only), cron job, stack up |
| `docker-compose.yml` | The demo stack (deployer stack + Caddy + `mc` tool profile) |
| `Caddyfile` | TLS + reverse proxy for `$QNOP_DEMO_DOMAIN` |
| `build-golden-state.sh` | Capture the current DB + bucket as the golden state |
| `reset-demo.sh` | The 12-hourly reset/redeploy job |

## One-time AWS setup (AWS CLI)

Region: `eu-central-1`. Steps in order — each is a single CLI action:

1. **Budget alarm** so nothing ever runs up cost unnoticed
   (`aws budgets create-budget`, e.g. 30 USD/month with an e-mail alert).
2. **Key pair** for SSH (`aws ec2 create-key-pair`).
3. **Security group**: inbound 80 + 443 from anywhere, 22 only from the
   operator's IP.
4. **Launch instance**: Amazon Linux 2023 arm64 AMI, `t4g.medium`,
   30 GiB gp3, the security group and key pair, `user-data.sh` as user
   data — the instance installs itself on first boot.
5. **Elastic IP** (`aws ec2 allocate-address` + `associate-address`) so
   the address survives instance rebuilds.
6. **DNS**: `A` record `demo.qnop.io → <elastic IP>` at the DNS
   provider. Caddy obtains the certificate automatically once the record
   resolves.

## Staging the demo content

1. After first boot the stack runs with an empty database. Retrieve the
   initial admin password from the app log
   (`docker compose logs qnop | grep -i password`).
2. Load the seed users, then create the example reviews (upload the demo
   PDFs, invite participants, add annotations) — see `testdata/README.md`
   for the seed and the staging notes.
3. Capture it: `sudo /opt/qnop-demo/build-golden-state.sh`.
4. Verify: `sudo /opt/qnop-demo/reset-demo.sh` and check the app still
   shows the staged content.

## Operations

```bash
ssh -i qnop-demo.pem ec2-user@<elastic-ip>

cd /opt/qnop-demo
sudo docker compose ps                  # stack status
sudo docker compose logs -f qnop        # app logs
sudo /opt/qnop-demo/reset-demo.sh       # manual reset/redeploy
sudo tail /var/log/qnop-demo-reset.log  # cron job log
```

Re-running `install.sh` from a fresh `git pull` of the repo updates the
scripts without touching `.env` or the golden state. To change the demo
content: stage the changes in the running app, then re-run
`build-golden-state.sh`.

## Cost

Roughly 25–30 USD/month: t4g.medium (~24 USD) + 30 GiB gp3 (~3 USD) +
minimal traffic — covered by AWS Activate credits. Teardown is
`aws ec2 terminate-instances` plus releasing the Elastic IP.
