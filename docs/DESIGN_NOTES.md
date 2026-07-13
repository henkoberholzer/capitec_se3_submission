# Design Notes — Secure Download System

## Problem Interpretation

**Assignment:** Develop a system to store customer account statements as PDF files and provide secure, time-limited download links.

**Interpretation:** The term "download link" implies unauthenticated access (as opposed to a deep link into an authenticated application). This system assumes a call-centre use case: agent selects a document, system generates a short-lived link, link is delivered to customer via phone/email, customer downloads without authentication.

**Impact:** No PII stored in the system. Compromised system leaks only temporary tokens and audit metadata, not customer account data.

### System of record vs delivery audit

SDS is **not** the system of record for statement PDFs. The upstream system (statement generator, agent platform, etc.) retains authoritative content. SDS:

- Holds PDF bytes only for the **time- and count-limited delivery window**
- Computes and stores **SHA-256** of the bytes accepted on upload
- Upload is **DB-first + streaming put**: insert `CREATING` + outbox (placeholder hash/size), stream body to object store while hashing, then promote `ACTIVE` with final hash/size (failed puts leave `CREATING` for outbox cleanup)
- After the window ends (outbox-driven), lifecycle is: **delete PDF → archive audit JSON (no PDF) → delete DB metadata**
- Does **not** copy PDF content into the archive bucket

In docs, always distinguish **object delete** (PDF) vs **metadata delete** (DB rows): the PDF leaves SDS quickly; operational rows leave only after the audit archive is durable.

The content hash exists so delivery activity can be **correlated to the upstream system** (re-hash the original or the bytes submitted; join to SDS `sha256Hash` and audit events). Investigations that need the actual statement go to upstream; SDS answers what happened to the link and which content fingerprint was delivered.

Full decision record: [ADR-005: Audit Archive, Not Document Store](adr-005-audit-archive-not-document-store.md). README outbox diagram: [Outbox Pattern](../README.md#outbox-pattern--document-lifecycle-events).

---

## Scalability Assumptions

### Initial Scale (Development)
- **Documents:** 100k–1M per month
- **Downloads:** 1–5 per document (typical)
- **Concurrent users:** 10–100 at peak
- **Storage:** ~10 GB documents, ~100 MB audit logs
- **Database:** Single PostgreSQL instance (sufficient)

### Projected Scale (Production, Year 2)
- **Documents:** 10M–50M per month (bulk batch generation at month-end)
- **Downloads:** Spike pattern — mostly at month-end when statements released
- **Concurrent downloads:** 10k–50k at peak
- **Storage:** ~1 TB documents, ~10 GB audit logs
- **Database:** Read replicas needed; horizontal partitioning on document_id

### What Changes at Scale

1. **Batch Document Generation**
   - Current: Single-threaded upload via REST API
   - Scale: Bulk ingest via S3 batch import + Kafka event stream
   - Implementation: Add IngestService to consume events from source system

2. **Download Path Scaling**
   - Current: Spring Boot services handle all streaming
   - Scale: Proxy offload to CDN (CloudFront + S3 presigned URLs for the streaming path)
   - Trade-off: Download-service becomes metadata-only gate; S3 serves bytes at hyperscaler scale
   - Implementation: Refactor to generate presigned URLs after auth gates (revocation, rate limit, count)

3. **Audit Storage** (metadata + content hash only — not PDF bytes; see [ADR-005](adr-005-audit-archive-not-document-store.md))
   - Current: PostgreSQL + Kafka → MinIO archive bucket (JSON audit payloads)
   - Scale: Kafka log compacted on document_id; archive to S3 Glacier for cold storage
   - Implementation: Lifecycle policy on archive bucket; retention per compliance

4. **Database**
   - Current: Monolithic PostgreSQL
   - Scale:
     - Read replicas for audit queries
     - Partitioning on document_id (hash-based) to shard across instances
     - Caching layer (Redis) for hot documents (frequent re-download attempts)

5. **Rate Limiting**
   - Current: In-memory Bucket4j (not cluster-aware)
   - Scale: Redis-backed rate limiter (shared state across all instances)
   - Implementation: Switch to Resilience4j + Redis backend

---

## Security Model

### Threat Model
1. **Attacker: Random internet user with no credentials**
   - Attack: Brute-force token enumeration (40 bits)
   - Defence: Rate limiting (5 req/min per IP), exponential backoff, token expiry (1 hour)
   - Risk: Low (1,400 years to exhaust under 100-IP distributed attack)

2. **Attacker: Insider/compromised employee**
   - Attack: Bulk download via generated links
   - Defence: Download-count limit (1–2), audit trail, revocation
   - Risk: Mitigated (limited damage, traceable)

3. **Attacker: Intercepted link (MITM)**
   - Attack: Use token to download
   - Defence: HTTPS enforced, token hashed at rest, token short-lived
   - Risk: Low (token valid only 1 hour by default)

4. **Attacker: Compromised service (e.g., download-service)**
   - Attack: Direct access to documents or metadata
   - Defence: Service separation — download-service has no DB/S3 credentials
   - Risk: Limited (attacker can only stream documents via management-service, still audited)

### Gaps (Production Readiness)
- No rate limiting per document ID (only per IP) — could allow targeted exhaustion
- No CAPTCHA or attestation (phone home) — distributed attack would succeed with patient botnet
- Token entropy (40 bits) below NIST guidelines (128 bits) — acceptable given rate limiting + expiry

---

## Operational Considerations

### Monitoring
- **Prometheus metrics:** Request latency, error rates, download success/failure rates
- **Grafana dashboards:** System health, document lifecycle, audit event volume
- **Alerts:** Failed outbox events, high error rate on download, revocation spike

### Failure Modes
1. **Storage service down:** Downloads return 500; customers retry later (acceptable)
2. **Database down:** Both services fail; no workaround (single point of failure)
3. **Kafka down:** Outbox events queue in DB; archive delayed but not lost
4. **Management-service down:** Download-service can't retrieve documents; return 503

### Graceful Degradation
- Download-service can cache frequently accessed documents in local disk cache
- Outbox processor can retry with exponential backoff (not yet implemented)
- RateLimiter can use local state if Redis is unreachable (degrades to per-instance)

---

## Known Limitations

### Code
- Outbox processor processes events sequentially within a single instance
- No connection pooling optimization for high concurrency (relying on Spring defaults)
- Virtual threads enabled on download-service (Tomcat request executor) but not on management-service
- Download slots: claimed before stream for concurrency safety; **released** if the stream does not complete (client disconnect / I/O error). Process crash mid-stream can still leave a reserved slot until manual/ops intervention (no lease sweeper yet).
- Outbox: claim sets `locked_until` + increments `attempt_count` (no PROCESSING status); expired leases are reclaimable. Failure uses exponential backoff on `process_after` until `max_attempts`, then `FAILED`. Handlers should stay idempotent if a lease expires mid-work.

### Data
- No soft-deletes; audit trail is immutable once archived (audit JSON + sha256 only; PDF not retained)
- No data anonymization; audit logs contain full document metadata forever
- Content hash is SDS-computed on upload; client-supplied hash verification and upstream document id are not yet on the API
- Token uniqueness enforced in DB (could use Bloom filter for scale)

### DevOps
- Keycloak runs in start-dev mode (not suitable for production)
- No multi-region replication or disaster recovery plan
- No secrets manager (local demo credentials via gitignored `.env` bootstrapped from `.env.example`)

---

## Future Work (Priority Order)

1. **Compliance & Legal**
   - [ ] Data retention policy (when to purge **audit** archives — not PDFs; PDFs are already deleted post-window)
   - [ ] Encryption at rest + in transit (TLS 1.3 enforced)
   - [ ] Audit trail immutability (WORM storage, signed hashes of audit payloads)
   - [ ] Optional: verify client-supplied SHA-256 on upload; accept upstream document/statement id for join ([ADR-005](adr-005-audit-archive-not-document-store.md))

2. **Scale & Performance**
   - [ ] Horizontal scaling: load balancer, session affinity for rate limiting
   - [ ] Redis rate limiter (shared state)
   - [ ] Database read replicas + partitioning
   - [ ] CDN proxy for download path

3. **Reliability**
   - [ ] Circuit breaker for S3 timeouts
   - [ ] Outbox processor with exponential backoff + dead-letter queue
   - [ ] Replica database for failover (active-passive)

4. **Observability**
   - [ ] Distributed tracing (OpenTelemetry)
   - [ ] Structured logging aggregation (ELK stack)
   - [ ] Custom metrics for business domain (e.g., revocation rate, average document size)

5. **Security Hardening**
   - [ ] WAF rules (token format validation)
   - [ ] CORS policy enforcement
   - [ ] Request signing (prevent CSRF)
   - [ ] Rate limiter bypass detection (pattern analysis)
