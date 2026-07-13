# ADR-001: Custom Download Proxy vs S3 Presigned URLs

**Status:** Accepted

**Date:** 2026-07-12

## Context

The Secure Download System needs to deliver PDFs to unauthenticated customers via secure, time-limited links. Two primary approaches exist:

1. **S3 Presigned URLs** — AWS S3 generates cryptographically-signed URLs with built-in TTL enforcement. Customer downloads directly from S3.
2. **Custom Download Proxy** — Application generates opaque tokens; download-service acts as a proxy, streaming bytes from S3 back through application logic.

The team operates under a Simplicity principle: prefer platform-provided capabilities over custom alternatives unless there is a defensible reason to diverge.

## Decision

**Implement a custom download proxy** rather than using S3 presigned URLs natively.

## Rationale

Presigned URLs alone cannot enforce three core requirements:

### 1. Download-Count Enforcement

**Requirement:** Each link permits a fixed number of downloads (typically 1–2). After that limit, the link must become invalid.

**Why presigned URLs fail:** A presigned URL is a self-contained token signed by AWS. Once issued, S3 has no way to track how many times it has been used or to reject it after N uses. The URL remains valid until its TTL expires, even if exhausted.

**Custom proxy solution:** Every download request hits the application. The application can atomically claim a download slot (row-level lock on the document record), verify the document is not exhausted, and reject the request with HTTP 410 if the limit is reached.

### 2. Revocation

**Requirement:** If a document is recalled or access is withdrawn, the link must become invalid immediately—without waiting for TTL expiry.

**Why presigned URLs fail:** The URL is valid until expiry. There is no revocation mechanism. Once issued, an administrator cannot kill the link early.

**Custom proxy solution:** Every request checks the document's current status (ACTIVE, REVOKED, ARCHIVED, EXHAUSTED) in the database. A revocation can take effect within seconds.

### 3. Audit-Trail Granularity

**Requirement:** Full audit of each download attempt, completion, and failure for compliance investigations and breach response.

**Why presigned URLs fall short:** S3 access logs record bucket-level events asynchronously with coarse granularity. They do not distinguish between a successful full download, a partial download (client disconnect), or an attempt that was rejected by rate limiting or other controls.

**Custom proxy solution:** Every request publishes a Kafka event (DOWNLOAD_ATTEMPT, DOWNLOAD_COMPLETE, DOWNLOAD_FAILED, DOWNLOAD_PARTIAL) with full context (document ID, token, IP, timestamp, bytes transferred). These events are archived durably for forensic analysis.

### 4. URL Opacity

**Requirement:** The download URL should not leak implementation details (bucket name, AWS signature format, object key structure).

**Why presigned URLs fail:** A presigned URL contains the bucket name, object key, and an AWS-specific signature format. A sophisticated attacker can learn the S3 bucket structure and signature algorithm. For financial institutions, this information leakage is a compliance risk.

**Custom proxy solution:** The download URL contains only an opaque token (e.g., `/download/a1b2c3d4e5f6`). The backend token-to-document mapping is hidden.

## Alternatives Considered

### Alternative 1: Presigned URL + Lambda@Edge for Download Counter

**Approach:** Use Lambda@Edge (CloudFront) to intercept requests and decrement a counter in DynamoDB before allowing the download.

**Why rejected:** Introduces additional complexity (Lambda + DynamoDB + consistent writes under high concurrency). Download-count must be strongly consistent; eventually-consistent DynamoDB or cache misses create correctness risks. For a financial system, the simplicity gain is marginal.

### Alternative 2: Presigned URL + Signed Cookie + Single-Use Nonce

**Approach:** Issue a presigned URL + a CloudFront signed cookie with a one-time-use nonce. After one download, the nonce is invalidated.

**Why rejected:** Requires coordination between URL issuance and nonce issuance. CloudFront doesn't natively support single-use nonces; implementing this requires Lambda@Edge or a custom origin. Still introduces more complexity than a direct proxy.

### Alternative 3: Use Presigned URLs for the Initial Submission; Accept Trade-offs

**Approach:** In production, use presigned URLs with CloudFront + WAF for rate limiting. Accept that download-count enforcement is not available natively.

**Why not chosen (initially):** The brief specifies download-count enforcement as a requirement. However, this remains a valid trade-off if the requirement is deprioritized in favor of operational simplicity.

## Trade-offs

| Dimension | Presigned URLs | Custom Proxy |
|---|---|---|
| **Compute in download path** | None (direct S3) | Two JVMs process every byte |
| **Operational burden** | Minimal (AWS manages) | Must scale/monitor two services |
| **Download-count enforcement** | Not supported | Fully supported |
| **Revocation** | Not supported | Fully supported (immediate) |
| **Audit granularity** | Coarse (S3 logs) | Granular (Kafka per-request) |
| **Latency** | Customer → S3 (or edge) | Customer → download-service → management-service → S3 |
| **Scaling at month-end** | Automatic (S3 handles scale) | Must horizontally scale services |
| **Security surface** | AWS-managed | Custom token scheme + rate limiter |

## Consequences

### Positive

- Download-count enforcement and revocation are first-class operations.
- Audit trail is forensic-grade; suitable for compliance audits and breach investigations.
- No implementation details leaked in URLs.
- Service separation enforces least privilege (download-service cannot access database or S3 credentials).

### Negative

- The team owns infrastructure (custom token generation, rate limiting, stream handling) that S3 presigned URLs provide for free.
- Higher operational overhead: two services must be scaled, monitored, and patched.
- Higher latency: bytes traverse two JVM processes instead of flowing directly from S3.
- Proxy layer consumes CPU and memory on high-volume downloads. Horizontal scaling required at month-end.

## Revisit Conditions

- **If download-count enforcement becomes optional:** Switch to presigned URLs + CloudFront, eliminating the download-service. Reduce two services to one.
- **If audit requirements relax to S3 access logs (coarse-grained):** Presigned URLs alone become viable; drop the proxy.
- **If platform constraint changes (non-AWS cloud, on-premise storage):** The proxy layer already abstracts storage; no refactor needed. Presigned URLs lock into AWS.

## Related Decisions

- [ADR-002: Token Entropy Trade-off](adr-002-token-entropy.md) — Defines the size and security properties of opaque tokens.
- [ADR-005: Audit Archive, Not Document Store](adr-005-audit-archive-not-document-store.md) — Audit trail retains metadata and content hash only; PDF is not archived.
- Service separation ([README: Architecture](../README.md#architecture)) — Download-service design rationale.
