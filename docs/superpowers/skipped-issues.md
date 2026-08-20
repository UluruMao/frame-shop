# Skipped Issues

Deliberate skip-and-return entries: things intentionally left undone (with a fallback in place) so the build stays green and flows keep working. Each line: what's skipped, why, and the trigger to revisit.

- AliyunVerificationProvider: SDK method names/artifact unconfirmed (could not fetch live doc 2026-08-19). Dev stub works; wire the real SDK when the Aliyun 短信认证服务 doc is available, then user manually verifies a real send.

## Deferred to the LAST stage (Phase F / final) — user decision 2026-08-20
- **Aliyun 短信认证服务 activation + real-SMS manual verification**: user will activate the service (短信服务 100条/3个月 free trial) and verify a real SMS send at the final stage. Until then, `LoggingVerificationProvider` (dev stub) is the active provider — all SMS flows work locally.
- **VM redeploy + e2e regression on the live VM**: user will do the VM redeploy (rebuild jar + scp + systemd restart) and the e2e auth-test contract update (register is now 5-arg: username+password+phone+code+agreed) at the final stage. Local suites (backend 56 + frontend 21) are the per-phase gate; the VM runs the old backend until the final stage.



