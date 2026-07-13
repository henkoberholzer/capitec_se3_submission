# ADR-004: Per-Pod Rate Limiting vs. Distributed Rate Limiting

**Status:** Accepted

**Date:** 2026-07-12

## Context

`download-service` rate-limits by client IP via an in-memory `ConcurrentHashMap<String, Bucket>` (`RateLimitFilter`), scoped to a single instance. Each replica tracks and enforces its own limit independently; there is no shared state (e.g. Redis) coordinating limits across replicas.

The alternative is a distributed rate limiter: a shared store (Redis, Hazelcast, etc.) that all `download-service` instances read/write to, giving one global count per client IP regardless of which instance handled the request.

The two options trade off differently on two axes:
- **Enforcement precision:** per-pod enforces the configured limit only against traffic that instance happens to see; a client hitting N replicas round-robin effectively gets N× the configured limit. Distributed enforces the true global limit.
- **Operational cost:** per-pod needs nothing extra. Distributed needs a shared store, a network hop on every request, and that store becomes a new dependency the download path can fail on.

## Decision

**Rate limiting stays per-pod (in-memory) for this iteration.** A distributed limiter is not implemented.

## Rationale

- **The threat this defends against doesn't need global precision.** Rate limiting here exists to slow brute-force/enumeration of the token space (see [ADR-002](adr-002-token-entropy.md)), not to enforce a hard usage quota. A distributed attacker who fans requests across replicas to multiply their effective rate still faces the same 40-bit token space — per-pod limiting raises the cost of brute-forcing by roughly the replica count, distributed limiting raises it further, but both are already layered under token expiry, download-count caps, and audit alerting. Per-pod is sufficient; distributed is a marginal improvement over an already-adequate control.
- **Replica count at this scale is small.** At the stated target scale (single-region, 100k–1M documents/month), `download-service` runs a small, known number of replicas. The "effective limit multiplier" from per-pod enforcement is bounded and predictable, not the unbounded gap it would be at large fleet sizes.
- **Avoids a new failure mode on the hot path.** A distributed limiter adds a network call (to Redis or similar) to every download request. If that store is slow or unavailable, the download path either fails open (losing the protection entirely) or fails closed (an availability outage caused by a rate-limiter dependency, on a customer-facing endpoint). Per-pod avoids introducing this dependency for a control whose job is to slow down abuse, not gate correctness.
- **Simplicity-first.** Per Capitec engineering principles, complexity should be introduced when a real requirement demands it. Nothing here currently demands global precision over per-pod approximation.

## Consequences

### Positive
- No new infrastructure dependency (Redis/Hazelcast) on the download path.
- No added latency or failure mode from a shared-store round trip on every request.
- Rate limiting logic stays simple: one `ConcurrentHashMap`, no network I/O, easy to reason about and test.

### Negative
- A distributed attacker spreading requests across replicas gets an effective rate limit multiplied by replica count, not the single configured limit.
- Enforcement is approximate, not exact, once there is more than one replica.
- If `download-service` scales out significantly, the gap between configured limit and effective limit grows and this trade-off should be re-evaluated (already flagged in [README: Known Limitations](../README.md#known-limitations)).

## Revisit Conditions

- **If replica count grows large** (the effective-limit multiplier becomes material rather than marginal) — move to a Redis-backed limiter, sharing counters across instances.
- **If rate limiting needs to become a hard quota** (not just an anti-enumeration slow-down) rather than a defense-in-depth layer — per-pod approximation is no longer acceptable and a distributed, precise limiter is required.
- **If a distributed brute-force attack against tokens is observed in practice** — tighten via a shared limiter, shorter TTLs, or per-document (not just per-IP) limiting.

## Related Decisions

- [ADR-002: Token Entropy Trade-off](adr-002-token-entropy.md) — the primary control this rate limiter supplements; establishes why 40-bit entropy plus layered defenses (of which this is one) is an accepted risk posture.
- [README: Known Limitations](../README.md#known-limitations) — flags per-IP-only, per-instance rate limiting as a known scale limitation.
