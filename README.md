# AIFI Pull Agent

Receiving-side companion of the [AIFI Anonymization Gateway](https://github.com/JeffreyVisser1/proxyAIFI)
(1.2+). It receives the gateway's "study ready" notification — a DICOM Key Object Selection
document (KOS manifest) — retrieves exactly the listed instances with WADO-RS through the
receiving DICOM Web Proxy (which passes the request through to the sending proxy) and
C-STOREs them to the destination chosen by the route name in the KOS. Both DICOM Web Proxies
stay unmodified.

- Crash-safe job spool: a KOS is acknowledged only after its job is on disk; every instance
  the destination accepts is recorded before the local copy is removed; a re-sent KOS never
  creates a second job.
- Waits and retries when the study is not (yet) complete at the source; fetches only the
  missing instances when a few are left.
- HTTPS only with certificate and host name verification, TLS 1.3/1.2 AEAD ciphers, OAuth2
  client credentials or API key, streaming multipart (no study in memory), secrets encrypted
  at rest, optional DICOM TLS.
- `check`, `status`, `retry`; Windows service (WinSW).

Manual (Dutch): [docs/HANDLEIDING.md](docs/HANDLEIDING.md)
Build: `mvn verify` (Java 11+) → `target/aifi-pull-agent-dist.zip`
