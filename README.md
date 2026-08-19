# RecoverPro

Multi-tenant collections/recovery platform. Spring Boot server (`server/`), React web frontend
(`web/`), Expo/React Native mobile app (`mobile/`), and a voice-assistant support service
(`tts-service/`).

## Documentation

Production-readiness work is tracked system-by-system in `docs/PRODUCTION-TASKLIST.txt`; each
completed system has an execution record at `docs/SYSTEM-NN-*.md`. Key reference docs:

- `docs/MIGRATION-POLICY.md` — database migration rules (expand/contract, immutability, destructive
  changes). Also repeated in-place as a header comment on the most recent file in
  `server/src/main/resources/db/migration/`.
- `docs/RUNBOOK-DEPLOY.md` — production deploy sequence.
- `docs/RUNBOOK-DR.md` — backup and disaster recovery.
- `docs/CONFIG-REFERENCE.md` — environment variable reference.
- `docs/INFRA-CURRENT.md` — deployment target and infrastructure decisions.
