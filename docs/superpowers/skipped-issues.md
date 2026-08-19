# Skipped Issues

Deliberate skip-and-return entries: things intentionally left undone (with a fallback in place) so the build stays green and flows keep working. Each line: what's skipped, why, and the trigger to revisit.

- AliyunVerificationProvider: SDK method names/artifact unconfirmed (could not fetch live doc 2026-08-19). Dev stub works; wire the real SDK when the Aliyun 短信认证服务 doc is available, then user manually verifies a real send.
