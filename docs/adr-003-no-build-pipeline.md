# ADR-003: Verification CI Without Deployment

**Status:** Accepted

**Date:** 2026-07-12

**Updated:** 2026-07-12 — replaced “no pipeline” with a verification-only GitHub Actions workflow; still no deploy or registry push.

## Context

12-Factor **Build, Release, Run** requires clean stage separation. It does **not** require a hosted deployment target.

For this take-home:

- There is **no** shared cluster, environment promotion path, or artifact registry consumer.
- The only runnable environment is **local Docker Compose** (`docker-compose.yml` + `.env` + `start-fresh.sh/ps1`).
- Multi-stage **Dockerfiles** already define the Build stage; Compose attaches config (Release) and starts processes (Run).
- Local scripts (`run-unit-tests.sh/ps1`, `run-e2e-tests.sh/ps1`) provide on-demand correctness signals.

What was missing was an **automated gate** that proves, on every push/PR, that the project still **builds and tests** — without inventing a fake deploy.

## Decision

1. **Ship a verification-only CI pipeline** (`.github/workflows/verify.yml`) that on push to `main` and on pull requests:
   - Runs **checkstyle + unit tests** for **both** `management-service` and `download-service` (`mvn test`, checkstyle bound to `validate`).
   - **Builds** both service Docker images with `push: false` (proves the Dockerfile build stage for this commit).
2. **Do not** push images to a registry, promote environments, or deploy anywhere.
3. **Docker Compose remains** the release + run mechanism for the only environment that exists (developer/reviewer machine).
4. **Local scripts stay the source of truth for manual runs**; CI mirrors the same Maven goals and Docker build contexts. `run-unit-tests.sh/ps1` runs **both** services so local and CI stay aligned.

## Rationale

- **Demonstrate build without a deploy target.** Image build + unit tests show the codebase is buildable and correct; absence of a registry does not block that proof.
- **Avoid process theatre.** A job that “deploys to nowhere” would add ceremony without capability. Verification without push is the honest middle ground.
- **12-Factor still holds.** Build (Dockerfile / `mvn package` inside the image), release (image + env in Compose), run (Compose) remain separate. CI automates *verification of build*, not invents a production path.
- **E2E stays local.** Full-stack e2e needs the Compose topology (Keycloak, Postgres, Kafka, MinIO, both services). That is still `./run-e2e-tests.sh` after `./start-fresh.sh` — optional later for CI if a self-hosted or Compose-in-CI runner is justified.

## Consequences

### Positive

- PR/main get an automated regression signal (checkstyle + unit tests + image build).
- Reviewers and panel can point at CI as evidence the project builds without standing up the full stack.
- No fake deploy target or registry credentials to maintain.

### Negative

- CI does not run e2e or the full Compose stack (by design for this scope).
- CI does not produce a promotable, signed release artifact for production (not required until a real target exists).
- Image build in CI may be slower than unit tests alone; Buildx GHA cache mitigates re-runs.

## Revisit Conditions

- **If a deployment target is introduced** (registry, cluster, hosted env) — extend the pipeline: push versioned images, run e2e against an ephemeral environment, deploy on merge to main.
- **If compliance needs auditable build provenance** — add signed attestations / SBOM on the image build job.
- **If e2e flakiness in local-only runs becomes a problem** — add a Compose-based e2e job (heavier runners, longer wall clock).

## Related Decisions

- [ADR-001: Custom Proxy vs Presigned URLs](adr-001-presigned-urls-vs-proxy.md)
- [ADR-002: Token Entropy Trade-off](adr-002-token-entropy.md)
- [README: Quick Start](../README.md#quick-start) — Compose-based release/run
- Workflow: [`.github/workflows/verify.yml`](../.github/workflows/verify.yml)
