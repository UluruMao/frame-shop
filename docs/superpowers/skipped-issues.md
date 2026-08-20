# Skipped Issues

Deliberate skip-and-return entries: things intentionally left undone (with a fallback in place) so the build stays green and flows keep working. Each line: what's skipped, why, and the trigger to revisit.

- AliyunVerificationProvider: SDK method names/artifact unconfirmed (could not fetch live doc 2026-08-19). Dev stub works; wire the real SDK when the Aliyun 短信认证服务 doc is available, then user manually verifies a real send.

## Deferred to the LAST stage (Phase F / final) — user decision 2026-08-20
- **Aliyun 短信认证服务 activation + real-SMS manual verification**: user will activate the service (短信服务 100条/3个月 free trial) and verify a real SMS send at the final stage. Until then, `LoggingVerificationProvider` (dev stub) is the active provider — all SMS flows work locally.
- **VM redeploy + e2e regression on the live VM**: user will do the VM redeploy (rebuild jar + scp + systemd restart) and the e2e auth-test contract update (register is now 5-arg: username+password+phone+code+agreed) at the final stage. Local suites (backend 56 + frontend 21) are the per-phase gate; the VM runs the old backend until the final stage.

## Phase D — Task 0 baseline (2026-08-20)
- **Pre-existing non-catalog e2e cascade failures (OUT OF SCOPE)**: Running the full e2e suite (`node run-tests.mjs` with `E2E_BASE_URL=http://localhost:8081`) against the live dev backend shows 30/44 passed. The 14 failures are all the known SMS-gated register cascade — the `auth` suite's "register a new user" returns HTTP 500 (the SMS-gated register cascade), which cascades into the `products`/`sale`/`checkout`/`concurrency` suites that depend on a registered user token (hence the 401s and "undefined token" errors). The in-scope catalog (7/7) and orders (11/11) suites are green. These cascade failures are explicitly out of scope per the Task 0 brief; do NOT try to fix them. They will be resolved when the VM/auth-test contract is updated at the final stage (see "Deferred to the LAST stage" above).



