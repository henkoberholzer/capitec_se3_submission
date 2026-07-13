# ADR-005: Audit Archive Only — PDF Not Retained; Hash Correlates to Upstream

**Status:** Accepted

**Date:** 2026-07-12

## Context

After a document’s delivery window ends (expiry, exhaustion, or revocation), SDS runs outbox-driven lifecycle cleanup in this order (plus poll delay): **(1) delete the PDF** from the live documents bucket, **(2) publish an audit “archive” payload** (metadata + hash + events, no PDF), **(3) remove operational database rows** (document + audit_events).

Two interpretations of “archive” compete:

1. **Document archive** — retain the PDF bytes in cold storage so SDS can re-serve or re-prove content later.
2. **Audit / lifecycle archive** — retain durable evidence of *what happened* to the document (who uploaded it, hash of bytes received, downloads, revokes, timestamps), and delete the PDF from SDS.

This decision also defines the role of the stored **SHA-256 content hash** relative to the systems that originally produce statements.

## Decision

1. **SDS is a secure delivery plane, not a system of record for statement content.**
2. **The PDF is not archived.** After the delivery window, object bytes are deleted from the live store and are not copied into the archive bucket.
3. **What is archived is delivery audit evidence only:** document metadata, lifecycle status, caller identity, download/revoke outcomes, and the **SHA-256 of the bytes SDS accepted**.
4. **The content hash is a correlation and integrity join key to the upstream system** that generated or selected the statement. Upstream remains responsible for retaining the authoritative PDF (if retention is required).

## Rationale

### Product boundary

The intended use case is time-limited, count-limited customer delivery (e.g. call centre issues a link after verbal identity verification). The originating system already holds the statement; SDS’s job is to issue a safe, short-lived access path and leave a forensic trail of delivery activity.

Persisting PDFs in SDS after delivery would:

- Expand the **blast radius** of a compromise (another store of statements).
- Risk turning SDS into a **second system of record**, with duplicate retention, legal hold, and access-control obligations.
- Contradict the design goal of **not storing PII / customer document content** longer than needed for delivery.

### What “archive” means in this codebase

| Stored after lifecycle | Not stored after lifecycle |
|---|---|
| SDS `documentId` | PDF / object bytes |
| `sha256Hash` of bytes received at upload | Re-downloadable statement content |
| `fileSizeBytes`, status, timestamps | |
| `createdBy` (service caller) | |
| Download / revoke / upload audit events | |
| Token hash (not raw token) | |

The MinIO **archive** bucket (via Kafka Connect sink of `document-archive-event`) holds **JSON audit payloads**, not PDFs. The **documents** bucket is the only place PDF bytes live, and only while the document is downloadable.

### Role of the content hash

On upload, SDS streams the body through a digest and persists `sha256Hash` (SHA-256, lowercase hex of the full PDF bytes accepted after the PDF magic-byte check).

That hash is **not** a substitute for keeping the file. It supports:

1. **Integrity while SDS holds the object** — bytes in object storage match what was accepted.
2. **Cross-system correlation** — the upstream system of record can re-hash its original (or the bytes it submitted) and join to SDS audit records: “we issued statement *S*; SDS recorded hash *H* and these download events.”
3. **Dispute / investigation support** without SDS becoming a document warehouse — proof is of *delivery of content matching H*, not reconstruction of the PDF from SDS after delete.

### What SDS can and cannot prove

**Can support (with upstream cooperation):**

- A document with hash *H* was accepted by SDS at time *T₀* by caller *C*.
- Link usage: attempts, completions, exhaustion, revocation, related IPs/metadata as recorded.
- That content held by SDS during the window matched *H*.

**Cannot support alone after object delete:**

- Reproducing the statement PDF from SDS.
- Proving content of a download without the customer or upstream still holding the file (or an independent copy).
- Acting as long-term statement retention for compliance that requires content archival — that obligation stays with the **upstream system of record**.

## Correlation model (upstream)

```text
Upstream (SoR)                         SDS (delivery + audit)
─────────────────                      ──────────────────────
Holds authoritative PDF                Holds PDF only for delivery window
Issues / selects statement             Accepts PDF bytes on upload
Computes or retains hash H             Computes sha256 on ingest → stores H
May send correlation ids (future)      Archives metadata + H + audit events
                                       Deletes PDF after window
```

**Current implementation:** SDS **computes** the hash on ingest and includes it in the archive payload. Correlation with upstream is offline/forensic: upstream re-hashes its original and matches `sha256Hash` (and preferably a shared business id — see Future).

**Stronger contract (recommended when integrating):**

| Mechanism | Purpose |
|---|---|
| Upstream retains PDF under its own id | System of record / legal retention |
| Optional upload header: client-supplied SHA-256 | SDS **verifies** stream digest equals claimed hash (reject on mismatch) |
| Optional upstream document / statement id | First-class join key in DB + archive (hash alone is awkward at volume) |
| SDS archive retains hash + that id + audit | Delivery evidence linked to SoR |

Hash algorithm and encoding for any client-supplied value must match SDS: **SHA-256, lowercase hex, over the full request body bytes**.

## Alternatives considered

### Alternative 1: Archive PDF bytes to cold storage

**Rejected.** Duplicates retention responsibility, increases breach impact, and blurs SDS into a document repository. Use only if a compliance requirement explicitly mandates a second copy *in SDS* (none assumed here).

### Alternative 2: Archive nothing after delete

**Rejected.** Banking delivery needs an investigation trail (who issued the link, whether it was used, revoke/exhaustion). Metadata + hash archive is the minimum durable evidence without keeping content.

### Alternative 3: Keep PDF in SDS indefinitely; no separate audit store

**Rejected.** Fights time-limited delivery and least-privilege design; operational and compliance cost without improving the customer download path.

## Consequences

### Positive

- Blast radius stays small: post-window SDS holds no statement bytes.
- Clear SoR boundary: upstream owns content retention; SDS owns delivery audit.
- Archive payload stays small and cheap to retain or ship to WORM/compliance stores.
- Hash provides a portable join to upstream without re-storing files.

### Negative / constraints

- After delete, SDS cannot re-issue the same bytes; the caller must upload again (or the product must re-fetch from upstream — out of scope).
- Investigations that need the actual PDF must go to **upstream** (or the customer’s copy), using the hash (and future correlation ids) to join.
- Hash without an upstream business id is a weaker join at high volume; integration should add a source document id when available.
- Client-supplied hash verification is **not** implemented yet; today hash is SDS-computed only.

### Operational note on lifecycle ordering

Two different “deletes” appear in the flow — do not mix them up:

| Step | Outbox event | What is removed / written |
|---|---|---|
| 1 | `DELETE_FILE` | **PDF object** in the live documents bucket (as soon as practical after expiry) |
| 2 | `ARCHIVE_DOCUMENT` | **Nothing deleted** — writes durable **audit JSON** (metadata + sha256 + events); status → ARCHIVED |
| 3 | `REMOVE_ARCHIVED_DOCUMENT` | **DB metadata** (`document` + `audit_events` rows) |

**Why PDF delete first is correct:** Archive never stores PDF bytes. Keeping expired objects only increases blast radius. Download is already blocked by expiry/status/count; the outbox delay is the only lag before the object is gone.

**What must stay ordered for metadata integrity:**

1. **`ARCHIVE_DOCUMENT` before `REMOVE_ARCHIVED_DOCUMENT`** — archive payload is built from DB rows; purge those rows only after Kafka/archive sink has the evidence.
2. **`DELETE_FILE` may run before archive** — and is scheduled first (`process_after` ≈ `expiresAt + 1m`, then archive, then DB remove). It must not run while the document is still downloadable for non-expiry reasons without the usual gates; after expiry, early PDF delete is intentional.

File delete need not “preserve the PDF for archive.” It must not race a still-valid download window (enforced by expiry / status / max downloads before outbox fires).

## Revisit conditions

- **Compliance requires SDS to retain statement content** — add an explicit document-retention path (separate from delivery audit), with encryption, access control, and legal hold; do not silently overload the audit archive.
- **Upstream cannot retain originals** — product must designate another SoR; SDS should not become that by accident.
- **High-volume correlation pain** — introduce mandatory upstream document id on upload and index it in archive payloads.
- **Non-repudiation beyond hash join** — consider signed audit events or WORM storage of the audit payload (hash of payload, not re-storage of PDF).

## Related decisions

- [ADR-001: Custom Proxy vs Presigned URLs](adr-001-presigned-urls-vs-proxy.md) — delivery path and audit granularity.
- [README: Architecture / Outbox](../README.md#outbox-pattern--document-lifecycle-events) — lifecycle diagram.
- [DESIGN_NOTES: Security & limitations](DESIGN_NOTES.md) — threat model and retention backlog.
