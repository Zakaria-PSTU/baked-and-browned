# ADR-0026: Key-bound refresh tokens (proof of possession) — future

- Status: Proposed (future work; not scheduled)
- Date: 2026-11-20
- Related: ADR-0023 (strict rotation with browser-side recovery), ADR-0025

## Context
Strict rotation with browser-side recovery (ADR-0023) leaves two gaps:
- A refresh response lost in the network, after the server committed, still signs the user out.
- Anyone who copies a *current* refresh cookie, from the cookie store, a proxy or device malware, can use it until the victim's next refresh.

Recovering a lost response safely needs proof that the retry comes from the original client. A token, a User-Agent, an IP address or a request id cannot give that proof, because each can be copied along with the request.

## Proposal
- **Key creation.** At login the SPA creates a WebCrypto ECDSA P-256 key pair with `extractable: false` and keeps the `CryptoKey` in IndexedDB. Login sends the public key (JWK). The server stores its RFC 7638 thumbprint on the family.
- **Proof on every refresh.** Each refresh sends a DPoP-style proof (RFC 9449 shape: `htm`, `htu`, `iat`, `jti`) signed with that key. The server verifies:
  - the signature against the stored thumbprint;
  - `iat` within ±60 s;
  - a `jti` not used before (the last proof id is stored on each token row).
- **Lost-response recovery.** A spent token may be presented again only with a **new, valid proof from the bound key**, within a short window and under a small cap. The undelivered successor is superseded, and the family lock from ADR-0025 applies.
- **What it defends.** A copied cookie, even together with a copied proof header, is useless: the proof is single-use and the key cannot be extracted. Script running in the page, or malware controlling the browser, can still use the key. Those already have full access today.
- **Dependencies.** No new backend dependency: Nimbus JOSE is already present through Spring Security's OAuth2 resource server. WebCrypto and IndexedDB are available in all supported browsers.
- **Costs.**
  - An API contract change: a public key at login, and a proof header on refresh.
  - A migration: the thumbprint per family, and the last proof id per token.
  - Clock-skew handling.
  - Clearing site data loses the key, so the user signs in again.
  - Families from before the migration, with no key, keep strict rotation until re-login.

## Decision
Deferred. Revisit if monitoring shows meaningful sign-outs from lost network responses, or before handling higher-value operations from customer sessions.
