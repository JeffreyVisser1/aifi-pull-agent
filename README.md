# AIFI Pull Agent

Receiving-side companion of the [AIFI Anonymization Gateway](https://github.com/JeffreyVisser1/proxyAIFI)
(2.0+). The gateway C-STOREs a KOS (Key Object Selection) to JiveX as its pseudonymization
trigger; JiveX routes its pseudonymized copy to this agent. The agent retrieves the study
with WADO-RS through the receiving DICOM Web Proxy (B, passthrough to proxy A, which C-MOVEs
it from the gateway pool) and C-STOREs it to the destination chosen by the route name. Both
DICOM Web Proxies stay unmodified.

- Tolerant of the PACS's pseudonymization profile: uses the StudyInstanceUID (the JiveX
  pseudonym, same as the pool), the route from the KOS text or Series Description, else from
  the private route tag the gateway writes into every instance, and the instance count from
  the text or the evidence sequence.
- Only KOS objects with an AIFI mark are accepted (Manufacturer, Series Description or text).
- Crash-safe job spool: a KOS is acknowledged only after its job is on disk; every instance
  the destination accepts is recorded before the local copy is removed; a re-sent KOS never
  creates a second job.
- Waits and retries while the gateway has not offered the study yet, and retrieves again
  when fewer instances than announced came back (already delivered ones are skipped).
- HTTPS only with certificate and host name verification, TLS 1.3/1.2 AEAD ciphers, OAuth2
  client credentials or API key, streaming multipart (no study in memory), secrets encrypted
  at rest, optional DICOM TLS.
- `check`, `status`, `retry`; Windows service (WinSW).

Manual (Dutch): [docs/HANDLEIDING.md](docs/HANDLEIDING.md)
Build: `mvn verify` (Java 11+) → `target/aifi-pull-agent-dist.zip`
