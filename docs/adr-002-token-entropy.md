# ADR-002: Token Entropy Trade-off — Usability vs. Security

**Status:** Accepted

**Date:** 2026-07-12

## Context

The download system issues time-limited, single-use bearer tokens to customers. These tokens are delivered verbally (call centre), via email, or SMS — channels that prioritize brevity and human transcription accuracy over cryptographic length.

The decision balances two competing pressures:
- **Usability:** Token must be short enough to read aloud (8–12 characters) and transcribed by hand without error.
- **Security:** Token must have sufficient entropy to resist brute-force enumeration, especially under distributed attack.

## Decision

**Token length: 8 Crockford characters (40 bits of entropy) by default, with configurable length via `token.length` setting.**

Crockford Base32 alphabet: `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (32 symbols, no confusables: O/I/L excluded).

## Rationale

### Entropy Calculation

8 Crockford characters:
- Alphabet size: 32 symbols
- Entropy per character: log₂(32) = 5 bits
- Total entropy: 8 × 5 = **40 bits**

Comparison to alternatives:
- 6-char Crockford: 30 bits (weak; ~1 billion possibilities)
- 8-char Crockford: 40 bits (1 trillion possibilities) — **chosen**
- 10-char Crockford: 50 bits (1 quadrillion possibilities)
- 12-char Crockford: 60 bits (1 sextillion possibilities)
- UUID v4 (base62): 128 bits (~10³⁸ possibilities, but 22 chars)

### Security Analysis Under Rate Limiting

**Single-attacker scenario (1 IP):**
- Rate limit: 5 requests per minute (typical RateLimitService.DEFAULT)
- Attempts per hour: 300
- Time to exhaust 40-bit space: 1 trillion ÷ 300/hr ÷ 24 = **≈ 141,000 years**
- Verdict: **Safe against single-source brute-force**

**Distributed attack (100 IPs, each rate-limited):**
- Requests per minute: 500
- Attempts per hour: 30,000
- Time to exhaust 40-bit space: 1 trillion ÷ 30,000/hr ÷ 24 = **≈ 1,400 years**
- Verdict: **Impractical without datacenter-scale resources**

**Distributed attack (1,000 IPs, each rate-limited):**
- Requests per minute: 5,000
- Time to exhaust 40-bit space: **≈ 140 years**
- Verdict: **Still impractical; diminishing returns on botnets**

### Mitigating Factors

1. **Token expiry:** Tokens are valid for a limited window (default: 1 hour). The search space shrinks over time.
2. **Download-count limit:** Most tokens permit 1–2 downloads. Exhaustion limits the window of opportunity per token.
3. **Audit trail:** Every attempt is logged. Distributed brute-force attempts would be immediately visible and can trigger alerts.
4. **Revocation:** If an attack is detected, tokens can be revoked server-side.

### Why Not Longer?

- **11-char Crockford (55 bits):** Still verbally communicable, but requires more careful transcription (error rate rises). Suitable for SMS or email, not voice calls.
- **UUID v4 (128 bits):** Too long for voice. Customer would need to write it down; defeats the convenience of verbal delivery.

### Why Not Shorter?

- **6-char Crockford (30 bits):** ~1 billion possibilities. Under 100-IP distributed attack, exhaustion would take ~3.8 years. Unacceptable for financial documents.
- **7-char Crockford (35 bits):** ~34 billion possibilities. Exhaustion under 100-IP attack: ~38 years. Borderline; not recommended.

## Decision: This is a Tunable Requirement

**This decision is NOT fixed. It depends on two external factors:**

1. **Usability constraint:** How short must tokens be for the delivery channel?
   - Voice call (call centre): 8 chars max (Crockford) — current choice
   - Email or SMS: 10–11 chars acceptable
   - Web/API: UUID or base64url acceptable

2. **Link validity duration:** How long are tokens valid?
   - Short TTL (30 min): Exhaustion under distributed attack takes longer (favorable)
   - Long TTL (24 hr): Exhaustion under distributed attack becomes feasible (unfavorable)
   - Current default: 1 hour

**Trade matrix:**

| Token Length | Entropy | Voice-Friendly? | Distributed Attack (100 IPs) | Recommended TTL |
|---|---|---|---|---|
| 6-char | 30 bits | Yes | 3.8 years | ≥ 24 hr |
| 7-char | 35 bits | Yes | 38 years | ≥ 12 hr |
| **8-char** | **40 bits** | **Yes** | **1,400 years** | **≥ 1 hr** ✓ |
| 10-char | 50 bits | Marginal | 1.4M years | ≥ 15 min |
| 11-char | 55 bits | Via SMS/email | 45M years | ≥ 5 min |
| UUID | 128 bits | No | Infeasible | ≥ 5 sec |

## Configuration

Token length is configurable at runtime via the `token.length` property (default: 8).

```yaml
# application.yml
capitec:
  sds:
    token:
      length: 8  # Crockford characters; valid range: 6–12
```

When changed, new tokens use the new length. Existing tokens of the old length continue to work until expiry.

## Implementation

See `TokenService.generateToken()` and `TokenService.TOKEN_LENGTH` configuration.

```java
@Value("${capitec.sds.token.length:8}")
private int tokenLength;

public String generateToken() {
  return CrockfordBase32.encode(generateRandomBytes(tokenLength));
}
```

## Consequences

### Positive

- 40 bits provides defensible security against distributed brute-force when combined with rate limiting, expiry, and audit trails.
- 8 characters is verbally communicable (call centre use case).
- Decision is tunable: if the threat model or delivery channel changes, adjust the config without code changes.

### Negative

- 40 bits is below NIST recommendations for high-entropy secrets (≥ 128 bits). However, this system supplements token entropy with:
  - Token hashing (raw token never stored in DB)
  - Time-based expiry
  - Download-count limits
  - Rate limiting
  - Audit trail

  These layers are not substitutes for entropy, but they collectively reduce the practical attack surface.

- If the token is captured in transit (e.g., logged in plaintext, intercepted email), a single attacker with patience and compute could eventually brute-force it. Mitigations:
  - HTTPS enforced for all token transmission
  - Tokens hashed at rest
  - Audit alerts on repeated failed attempts

## Revisit Conditions

- **If delivery channel changes from voice to email/SMS:** Increase to 10–11 chars (50–55 bits).
- **If delivery channel becomes API-only:** Switch to UUID or base64url (128 bits).
- **If link validity extends to 24+ hours:** Increase to 10 chars minimum.
- **If distributed attack becomes a known threat:** Increase length + reduce TTL + add CAPTCHA or attestation.
- **If compliance audit requires ≥ 128 bits:** Switch to UUID; accept longer URLs. Or use UUID as transport token + encrypt/obfuscate in URL.

## Related Decisions

- [ADR-001: Custom Proxy vs Presigned URLs](adr-001-presigned-urls-vs-proxy.md) — Context for why custom tokens are needed.
- [README: Security Measures](../README.md#security-measures) — Rate limiting and audit trail design.
