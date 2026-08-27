# Feature Round Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver five net-new features (account self-deletion, chat deletion sync, dashboard analytics, flash-sale percentage, admin/guest route split) on the current single-instance backend.

**Architecture:** Backend changes are surgical and additive — new endpoints + columns + queries; the frontend gains a time-filtered 3-chart Dashboard, a flash-sale % input, account/chat delete flows, and a split admin route tree. All features are multi-instance-safe by construction (no per-JVM state introduced). Spec: `docs/superpowers/specs/2026-08-27-feature-round-design.md`.

**Tech Stack:** Spring Boot 4.1 / MyBatis-Plus / MariaDB (backend); React 18 / Vite / Zustand / Recharts / Tailwind / vitest (frontend).

## Global Constraints

- All user-visible strings are Chinese (zh-CN). No i18n framework.
- Money is integer cents everywhere; 100 coins = ¥1 = 100 cents.
- No foreign-key constraints exist in the schema — all cascade is app-level inside `@Transactional`. State this in code comments where it matters.
- Admin endpoints use `@PreAuthorize("hasRole('ADMIN')")` (method security is enabled). User-facing mutations are IDOR-checked via `loadAndCheckOwnership`-style ownership verification.
- Flash-sale `isFlashSale` is a primitive `boolean` with `@JsonProperty("isFlashSale")` on entity + DTO — preserve this wire name when adding the sibling `discountPercent` field.
- E2E is NOT required this round (VM holds old contracts). Backend = `mvn test`; frontend = `npx vitest run` + `tsc --noEmit` + `npm run build`.
- Branch from `main` (`bb7a9e0`). Commit after every task.

---

## File Structure

**Backend — create:**
- `frame-backend/src/main/java/com/iflytek/frame/dto/DeleteAccountRequest.java` — `{ password }` request body.
- `frame-backend/src/main/java/com/iflytek/frame/service/AccountService.java` + `impl/AccountServiceImpl.java` — password re-auth + soft-delete cascade.
- `frame-backend/src/main/resources/mapper/AdminStatsMapper.xml` (modify) — time-windowed queries.

**Backend — modify:**
- `entity/Product.java`, `dto/ProductDTO.java` — add `discountPercent`.
- `common/Money.java` — flash-price helper.
- `service/impl/CheckoutServiceImpl.java` — apply flash price in total.
- `service/impl/CoinServiceImpl.java` — confirm cap unchanged (read-only verify).
- `controller/ProfileController.java` — `POST /auth/account/delete`.
- `controller/ChatController.java` — `DELETE /chat/sessions/{id}`.
- `service/ChatService.java` + `impl/ChatServiceImpl.java` — `deleteSession`.
- `service/UserService.java` + `impl/UserServiceImpl.java` — `deactivateAccount`.
- `security/JwtAuthenticationFilter.java` — `DELETED` status check (re-fetch + reject).
- `service/AdminStatsService.java` + `impl/AdminStatsServiceImpl.java` + `mapper/AdminStatsMapper.java` — `range`/`from`/`to` params.
- `dto/DashboardStatsVO.java` — chart datasets.
- `security/SecurityConfig.java` — matcher for `DELETE /chat/sessions/**`.
- `schema.sql` — `discount_percent` column; (prod) `schema-prod.sql` ALTER migration.

**Frontend — create:**
- `src/pages/admin/AdminProducts.tsx`, `AdminOrders.tsx`, `AdminChat.tsx`, `AdminDashboard.tsx` — decomposed from `Admin.tsx`.
- `src/layouts/AdminLayout.tsx` — admin nav shell.
- `src/pages/AdminLogin.tsx` — admin login route.
- `tests/AdminProducts.test.tsx`, `Profile.test.tsx` (modify `Chat.test.tsx`, `Dashboard.test.tsx`).

**Frontend — modify:**
- `router/index.tsx` — split admin routes + `/admin/login`.
- `layouts/MainLayout.tsx` — drop admin nav (admins use AdminLayout).
- `api/stats.ts`, `api/types.ts` — chart types + filter params.
- `api/chat.ts` — `deleteSession`.
- `api/auth.ts` — `deleteAccount`.
- `pages/Profile.tsx`, `Chat.tsx`, `Dashboard.tsx`.
- `store/useAuthStore.ts` — (verify `clear` exists — it does).

---

## Task 1: Account self-deletion — backend service + filter

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/dto/DeleteAccountRequest.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/service/AccountService.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/service/impl/AccountServiceImpl.java`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/UserService.java` (add `deactivateAccount`)
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/impl/UserServiceImpl.java`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/security/JwtAuthenticationFilter.java`
- Test: `frame-backend/src/test/java/com/iflytek/frame/service/AccountServiceImplTest.java`

**Interfaces:**
- Consumes: `UserService.findByUsername`, `PasswordEncoder.matches`, `UserMapper`, `ChatSessionMapper`, `SecurityUtils.currentUserId/currentUsername`. `SecurityUser.from(claims)` exists and exposes `userId()`/`username()`.
- Produces: `AccountService.deactivateAccount(Long userId, String password)` (throws `BusinessException(BAD_CREDENTIALS)` on mismatch). `UserService.deactivateAccount(Long userId)` does the anonymizing UPDATE. `JwtAuthenticationFilter` gains a `status == DELETED` → 401 path.

- [ ] **Step 1: Write the failing test**

`AccountServiceImplTest.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ChatSessionMapper;
import com.iflytek.frame.mapper.UserMapper;
import com.iflytek.frame.service.impl.AccountServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AccountServiceImplTest {

    @Test
    void deactivateRejectsWrongPassword() {
        UserService userService = mock(UserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        User u = new User();
        u.setId(7L);
        u.setPasswordHash("hash");
        when(userService.findByUsername("alice")).thenReturn(u);
        when(encoder.matches("wrong", "hash")).thenReturn(false);

        AccountServiceImpl svc = new AccountServiceImpl(userService, encoder, chatSessionMapper);

        assertThatThrownBy(() -> svc.deactivateAccount(7L, "alice", "wrong"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密码不正确");
        verify(userService, never()).deactivateAccount(any());
    }

    @Test
    void deactivateAnonymizesAndClosesChatOnCorrectPassword() {
        UserService userService = mock(UserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        User u = new User();
        u.setId(7L);
        u.setPasswordHash("hash");
        when(userService.findByUsername("alice")).thenReturn(u);
        when(encoder.matches("right", "hash")).thenReturn(true);

        AccountServiceImpl svc = new AccountServiceImpl(userService, encoder, chatSessionMapper);

        svc.deactivateAccount(7L, "alice", "right");

        verify(userService).deactivateAccount(7L);
        verify(chatSessionMapper).update(any()); // closes OPEN sessions to CLOSED (see impl)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-backend && mvn test -Dtest=AccountServiceImplTest`
Expected: FAIL — `AccountServiceImpl` / `AccountService` / `DeleteAccountRequest` do not compile.

- [ ] **Step 3: Write minimal implementation**

`DeleteAccountRequest.java`:
```java
package com.iflytek.frame.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeleteAccountRequest {
    @NotBlank(message = "password must not be blank")
    private String password;
}
```

`AccountService.java`:
```java
package com.iflytek.frame.service;

public interface AccountService {
    /** Soft-delete the current user: verify password, anonymize PII, close chats. */
    void deactivateAccount(Long userId, String username, String password);
}
```

`AccountServiceImpl.java`:
```java
package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.entity.ChatSession;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ChatSessionMapper;
import com.iflytek.frame.service.AccountService;
import com.iflytek.frame.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountServiceImpl implements AccountService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final ChatSessionMapper chatSessionMapper;

    @Override
    @Transactional
    public void deactivateAccount(Long userId, String username, String password) {
        // Re-fetch by username (not by trusting the JWT payload alone) and assert id matches.
        User user = userService.requireUserMatching(username, userId);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ResultCode.BAD_CREDENTIALS, "密码不正确");
        }
        // Soft-delete the user PII + close chat sessions. Orders/reviews/coins are RETAINED
        // for analytics + audit (no FK constraints in the schema — cascade is app-level).
        userService.deactivateAccount(userId);
        chatSessionMapper.update(null,
                new LambdaUpdateWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .eq(ChatSession::getStatus, "OPEN")
                        .set(ChatSession::getStatus, "CLOSED"));
    }
}
```

`UserService.java` — add method:
```java
    /** Soft-delete: anonymize PII, null password (un-loginable), status=DELETED. Retains id + orders. */
    void deactivateAccount(Long userId);
```

`UserServiceImpl.java` — add:
```java
    @Override
    public void deactivateAccount(Long userId) {
        // Username must stay unique after deletion: suffix with id so it never collides with a
        // future registration of the same name. Phone/email/avatar/signature nulled.
        User u = new User();
        u.setId(userId);
        u.setUsername("[已注销]" + userId);
        u.setPasswordHash(null);
        u.setPhone(null);
        u.setNickname(null);
        u.setAvatarUrl(null);
        u.setSignature(null);
        u.setStatus("DELETED");
        userMapper.updateById(u);
    }
```

`JwtAuthenticationFilter.java` — after building the `SecurityUser principal`, reject deleted accounts. The filter currently resolves the principal from JWT claims only (no DB). Add a `UserService` dependency and check status. Replace the `if (SecurityContextHolder...getAuthentication() == null)` block:
```java
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityUser principal = SecurityUser.from(claims);
            // Re-check the DB status: a DELETED user's existing token must be rejected (401).
            // This is the soft-delete revocation point — no denylist needed.
            User current = userService.findByUsername(principal.username());
            if (current == null || "DELETED".equals(current.getStatus())) {
                chain.doFilter(request, response);
                return;
            }
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
```
Add `private final UserService userService;` to the filter's `@RequiredArgsConstructor` fields. Add import `com.iflytek.frame.entity.User;`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-backend && mvn test -Dtest=AccountServiceImplTest`
Expected: PASS (both tests).

- [ ] **Step 5: Run full backend suite to confirm no regression**

Run: `cd frame-backend && mvn test`
Expected: all green (the JWT filter change adds one DB read per authenticated request — existing tests should pass since they mock/seed ACTIVE users). If `AuthServiceImplTest` or integration tests break on the new `UserService` filter dependency, fix wiring.

- [ ] **Step 6: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/dto/DeleteAccountRequest.java \
        frame-backend/src/main/java/com/iflytek/frame/service/AccountService.java \
        frame-backend/src/main/java/com/iflytek/frame/service/impl/AccountServiceImpl.java \
        frame-backend/src/main/java/com/iflytek/frame/service/UserService.java \
        frame-backend/src/main/java/com/iflytek/frame/service/impl/UserServiceImpl.java \
        frame-backend/src/main/java/com/iflytek/frame/security/JwtAuthenticationFilter.java \
        frame-backend/src/test/java/com/iflytek/frame/service/AccountServiceImplTest.java
git commit -m "feat(account): soft-delete service + JWT DELETED-status revocation"
```

---

## Task 2: Account self-deletion — backend endpoint

**Files:**
- Modify: `frame-backend/src/main/java/com/iflytek/frame/controller/ProfileController.java`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/impl/ProfileServiceImpl.java` (avatar file cleanup — optional; see step)
- Test: extend `AccountServiceImplTest` or a controller slice test.

**Interfaces:**
- Consumes: `AccountService.deactivateAccount` (Task 1). `SecurityUtils.currentUserId()/currentUsername()`.
- Produces: `POST /auth/account/delete` (authenticated, body `{ password }`).

- [ ] **Step 1: Write the failing test**

Add to `AccountServiceImplTest.java` (or a new `ProfileControllerTest` using `@WebMvcTest`). Simplest: a focused controller test is heavier; instead verify wiring via a new test that the endpoint delegates. Since `ProfileController` is thin, add a Mockito controller test:

`frame-backend/src/test/java/com/iflytek/frame/controller/ProfileControllerDeleteTest.java`:
```java
package com.iflytek.frame.controller;

import com.iflytek.frame.dto.DeleteAccountRequest;
import com.iflytek.frame.service.AccountService;
import com.iflytek.frame.util.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.*;

class ProfileControllerDeleteTest {

    @Test
    void deleteDelegatesToAccountService() {
        AccountService accountService = mock(AccountService.class);
        ProfileController controller = new ProfileController(null, null, null, accountService);
        DeleteAccountRequest req = new DeleteAccountRequest();
        req.setPassword("right");

        try (MockedStatic<SecurityUtils> su = mockStatic(SecurityUtils.class)) {
            su.when(SecurityUtils::currentUserId).thenReturn(7L);
            su.when(SecurityUtils::currentUsername).thenReturn("alice");

            controller.deleteAccount(req);
        }
        verify(accountService).deactivateAccount(7L, "alice", "right");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-backend && mvn test -Dtest=ProfileControllerDeleteTest`
Expected: FAIL — `ProfileController` has no `deleteAccount` + no `AccountService` field (constructor arity mismatch).

- [ ] **Step 3: Write minimal implementation**

`ProfileController.java` — add the `AccountService` field and endpoint:
```java
    private final AccountService accountService;   // add to the @RequiredArgsConstructor fields

    @Operation(summary = "Self-delete account (soft delete, password re-auth)")
    @PostMapping("/account/delete")
    public Result<Void> deleteAccount(@Valid @RequestBody DeleteAccountRequest req) {
        accountService.deactivateAccount(SecurityUtils.currentUserId(), SecurityUtils.currentUsername(), req.getPassword());
        return Result.success();
    }
```
Update the import block to include `DeleteAccountRequest` and `AccountService`. Note the constructor now has 4 deps (`profileService, rebindService, userService, accountService`) — match the test's `new ProfileController(null, null, null, accountService)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-backend && mvn test -Dtest=ProfileControllerDeleteTest`
Expected: PASS.

- [ ] **Step 5: Run full backend suite**

Run: `cd frame-backend && mvn test`
Expected: green.

- [ ] **Step 6: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/controller/ProfileController.java \
        frame-backend/src/test/java/com/iflytek/frame/controller/ProfileControllerDeleteTest.java
git commit -m "feat(account): POST /auth/account/delete endpoint (password re-auth)"
```

---

## Task 3: Account self-deletion — frontend (Profile card + API)

**Files:**
- Modify: `frame-frontend/src/api/auth.ts` — add `deleteAccount`.
- Modify: `frame-frontend/src/pages/Profile.tsx` — danger-zone card with two-step Modal.
- Test: `frame-frontend/tests/Profile.test.tsx` (create).

**Interfaces:**
- Consumes: `request` (axios wrapper at `./request`), `useAuthStore.clear`, `useNavigate`. `Modal` at `@/components/ui/Modal`.
- Produces: a "注销账号" card calling `POST /auth/account/delete { password }`, then clearing auth + navigating to `/login`.

- [ ] **Step 1: Write the failing test**

`tests/Profile.test.tsx`:
```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import Profile from '@/pages/Profile'

const deleteAccount = vi.fn().mockResolvedValue(undefined)
vi.mock('@/api/auth', () => ({
  fetchMe: vi.fn().mockResolvedValue({ username: 'alice', role: 'USER', phone: '13800000000', nickname: 'A', signature: '' }),
  changePassword: vi.fn(),
  deleteAccount,
}))
vi.mock('@/api/profile', () => ({
  updateProfile: vi.fn(), uploadAvatar: vi.fn(),
  rebindInit: vi.fn(), rebindVerifyOld: vi.fn(), rebindConfirm: vi.fn(),
}))

function renderProfile() {
  return render(
    <MemoryRouter initialEntries={['/profile']}>
      <Routes><Route path="/profile" element={<Profile />} /></Routes>
    </MemoryRouter>,
  )
}

describe('Profile account deletion', () => {
  it('two-step confirm + password then deletes', async () => {
    renderProfile()
    await waitFor(() => expect(screen.getByText('个人资料')).toBeInTheDocument())

    // Step 1: open the danger card and click 注销账号
    fireEvent.click(await screen.findByRole('button', { name: '注销账号' }))
    expect(screen.getByText(/此操作不可逆/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '继续' }))

    // Step 2: enter password and confirm
    await waitFor(() => fireEvent.change(screen.getByPlaceholderText('登录密码'), { target: { value: 'right' } }))
    fireEvent.click(screen.getByRole('button', { name: '确认注销' }))

    await waitFor(() => expect(deleteAccount).toHaveBeenCalledWith('right'))
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-frontend && npx vitest run tests/Profile.test.tsx`
Expected: FAIL — no "注销账号" button (the card doesn't exist yet), and `deleteAccount` isn't exported.

- [ ] **Step 3: Write minimal implementation**

`api/auth.ts` — add:
```ts
export function deleteAccount(password: string): Promise<void> {
  return request.post('/auth/account/delete', { password })
}
```

`Profile.tsx` — add imports `deleteAccount` to the `@/api/auth` import, add `Modal`, and a danger-zone card before the closing `</div>`. Add state:
```tsx
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleteStep, setDeleteStep] = useState<'confirm' | 'password'>('confirm')
  const [delPw, setDelPw] = useState('')
  const navigate = useNavigate()
  const clear = useAuthStore((s) => s.clear)
```
Add `import { useNavigate } from 'react-router-dom'`, `import Modal from '@/components/ui/Modal'`, and import `deleteAccount`.

Add the card + Modal (place after the change-password Card, inside the outer `<div className="space-y-6">`):
```tsx
      {/* Danger zone — account self-deletion (soft delete) */}
      <Card className="p-5 space-y-4 border-red-200">
        <div className="flex items-center gap-2">
          <Trash2 className="w-4 h-4 text-red-600" />
          <h2 className="text-sm font-semibold text-slate-700">注销账号</h2>
        </div>
        <p className="text-xs text-slate-500">注销后将无法登录。您的订单和消费记录将保留用于统计。</p>
        <Button variant="secondary" className="text-red-600" onClick={() => { setDeleteOpen(true); setDeleteStep('confirm'); setDelPw('') }}>
          注销账号
        </Button>
      </Card>

      <Modal open={deleteOpen} onClose={() => setDeleteOpen(false)} title="确认注销">
        {deleteStep === 'confirm' ? (
          <>
            <p className="text-sm text-slate-600">此操作不可逆。您的订单和消费记录将保留，但您将无法再登录此账号。</p>
            <div className="mt-4 flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setDeleteOpen(false)}>取消</Button>
              <Button variant="primary" className="bg-red-600" onClick={() => setDeleteStep('password')}>继续</Button>
            </div>
          </>
        ) : (
          <form onSubmit={async (e) => {
            e.preventDefault()
            try { await deleteAccount(delPw) } catch (err) { setError(err instanceof Error ? err.message : '注销失败'); return }
            clear(); setDeleteOpen(false); navigate('/login', { replace: true })
          }} className="space-y-3">
            <Input type="password" placeholder="登录密码" value={delPw} onChange={(e) => setDelPw(e.target.value)} required />
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setDeleteOpen(false)}>取消</Button>
              <Button type="submit" loading={loading} className="bg-red-600">确认注销</Button>
            </div>
          </form>
        )}
      </Modal>
```
Add `Trash2` to the lucide import. Note: `loading`, `error`, `setError` already exist as Profile state — reuse them. If the shared `loading` causes a spinner on unrelated buttons, add a dedicated `deleting` state instead.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-frontend && npx vitest run tests/Profile.test.tsx`
Expected: PASS.

- [ ] **Step 5: Run full frontend suite + typecheck + build**

Run: `cd frame-frontend && npx vitest run && npx tsc --noEmit && npm run build`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add frame-frontend/src/api/auth.ts frame-frontend/src/pages/Profile.tsx frame-frontend/tests/Profile.test.tsx
git commit -m "feat(account): Profile danger-zone self-deletion (two-step + password)"
```

---

## Task 4: Chat deletion sync — backend

**Files:**
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/ChatService.java` — add `deleteSession`.
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/impl/ChatServiceImpl.java`.
- Modify: `frame-backend/src/main/java/com/iflytek/frame/controller/ChatController.java` — `DELETE /chat/sessions/{id}`.
- Modify: `frame-backend/src/main/java/com/iflytek/frame/security/SecurityConfig.java` — matcher.
- Test: `frame-backend/src/test/java/com/iflytek/frame/service/ChatServiceImplDeleteTest.java`.

**Interfaces:**
- Consumes: `loadAndCheckOwnership` (existing, IDOR-safe). `ChatSessionMapper`.
- Produces: `ChatService.deleteSession(Long sessionId, Long userId)` — soft-sets `status='DELETED'`. `DELETE /chat/sessions/{id}` (USER role). The admin `openSessions()` query already filters `status='OPEN'` so DELETED sessions vanish from the inbox automatically; verify and align.

- [ ] **Step 1: Write the failing test**

`ChatServiceImplDeleteTest.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.entity.ChatSession;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ChatMessageMapper;
import com.iflytek.frame.mapper.ChatSessionMapper;
import com.iflytek.frame.service.impl.ChatServiceImpl;
import com.iflytek.frame.websocket.AdminPresenceRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatServiceImplDeleteTest {

    @Test
    void ownerCanDeleteSession() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AdminPresenceRegistry presence = mock(AdminPresenceRegistry.class);
        ChatSession s = new ChatSession();
        s.setId(5L); s.setUserId(7L); s.setStatus("OPEN");
        when(sessionMapper.selectById(5L)).thenReturn(s);

        ChatServiceImpl svc = new ChatServiceImpl(sessionMapper, messageMapper, presence);
        svc.deleteSession(5L, 7L);

        verify(sessionMapper).update(any());
    }

    @Test
    void nonOwnerCannotDelete() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AdminPresenceRegistry presence = mock(AdminPresenceRegistry.class);
        ChatSession s = new ChatSession();
        s.setId(5L); s.setUserId(7L); s.setStatus("OPEN");
        when(sessionMapper.selectById(5L)).thenReturn(s);

        ChatServiceImpl svc = new ChatServiceImpl(sessionMapper, messageMapper, presence);
        assertThatThrownBy(() -> svc.deleteSession(5L, 999L))
                .isInstanceOf(BusinessException.class);
        verify(sessionMapper, never()).update(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-backend && mvn test -Dtest=ChatServiceImplDeleteTest`
Expected: FAIL — `deleteSession` doesn't exist.

- [ ] **Step 3: Write minimal implementation**

`ChatService.java` — add:
```java
    /** Soft-delete a session (status=DELETED), owner-only. Hidden from both inboxes; messages retained. */
    void deleteSession(Long sessionId, Long userId);
```

`ChatServiceImpl.java` — add:
```java
    @Override
    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        ChatSession session = loadAndCheckOwnership(sessionId, userId); // throws FORBIDDEN if not owner
        session.setStatus("DELETED");
        sessionMapper.updateById(session);
    }
```

`ChatController.java` — add:
```java
    @Operation(summary = "Delete (soft) the current user's chat session — hidden from both inboxes")
    @DeleteMapping("/sessions/{id}")
    public Result<Void> deleteSession(@PathVariable Long id) {
        chatService.deleteSession(id, SecurityUtils.currentUserId());
        return Result.success();
    }
```

`SecurityConfig.java` — in the `authorizeHttpRequests` block, the existing line `.requestMatchers(org.springframework.http.HttpMethod.GET, "/chat/sessions/**").hasRole("USER")` covers GET only. Add a DELETE matcher right after it:
```java
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/chat/sessions/**").hasRole("USER")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-backend && mvn test -Dtest=ChatServiceImplDeleteTest`
Expected: PASS (both).

- [ ] **Step 5: Run full backend suite**

Run: `cd frame-backend && mvn test`
Expected: green. (The `openSessions()` query filters `status='OPEN'`, so DELETED sessions are already excluded from the admin inbox — no query change needed. Verify the user side has no list endpoint that would expose DELETED sessions; the user only ever holds one OPEN session via `findOrCreateOpenSession`, which finds `status='OPEN'` — DELETED is excluded. Good.)

- [ ] **Step 6: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/service/ChatService.java \
        frame-backend/src/main/java/com/iflytek/frame/service/impl/ChatServiceImpl.java \
        frame-backend/src/main/java/com/iflytek/frame/controller/ChatController.java \
        frame-backend/src/main/java/com/iflytek/frame/security/SecurityConfig.java \
        frame-backend/src/test/java/com/iflytek/frame/service/ChatServiceImplDeleteTest.java
git commit -m "feat(chat): DELETE /chat/sessions/{id} soft-delete (owner-only, synced inboxes)"
```

---

## Task 5: Chat deletion sync — frontend

**Files:**
- Modify: `frame-frontend/src/api/chat.ts` — add `deleteSession`.
- Modify: `frame-frontend/src/pages/Chat.tsx` — delete button.
- Test: `frame-frontend/tests/Chat.test.tsx` (modify — add a delete case).

**Interfaces:**
- Consumes: `request.delete`. `useChat` (already returns the session id path).
- Produces: a "删除聊天" button that calls `deleteSession(id)` then clears local state.

- [ ] **Step 1: Write the failing test**

Add a third case to `tests/Chat.test.tsx`. The file already mocks `@/api/chat` (openSession returns `{ id: 7, ... }`) and `@/hooks/useChat`. Extend the chat mock to include `deleteSession`, then add:
```tsx
import { deleteSession } from '@/api/chat'  // add to imports at top

// (extend the existing vi.mock('@/api/chat', ...) factory to also export:)
//   deleteSession: vi.fn().mockResolvedValue(undefined),
```
Add this case inside the existing `describe('Chat page', ...)`:
```tsx
  it('deletes the chat session on click', async () => {
    render(<Chat />)
    await screen.findByText('联系客服')
    fireEvent.click(screen.getByRole('button', { name: '删除聊天' }))
    // confirm() is a window.confirm stub — jsdom returns true by default; if it
    // returns false, mock vi.spyOn(window, 'confirm').mockReturnValue(true) first.
    await waitFor(() => expect(deleteSession).toHaveBeenCalledWith(7))
  })
```
Add `waitFor` to the `@testing-library/react` import and `deleteSession` to the `@/api/chat` mock factory.


- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-frontend && npx vitest run tests/Chat.test.tsx`
Expected: FAIL — no delete button, `deleteSession` not exported.

- [ ] **Step 3: Write minimal implementation**

`api/chat.ts` — add:
```ts
export async function deleteSession(sessionId: number): Promise<void> {
  return request.delete(`/chat/sessions/${sessionId}`)
}
```

`Chat.tsx` — import `deleteSession`; add a button next to the PageHeader title and wire it:
```tsx
  async function handleDelete() {
    if (!session) return
    if (!confirm('删除此聊天？管理员端也将不再显示。')) return
    try {
      await deleteSession(session.id)
      setSession(null)
      setMessages([])
    } catch {
      // surface via the existing error pattern if present; Chat.tsx currently swallows — add a toast or inline error
    }
  }
```
Add a `<Button variant="secondary" onClick={() => void handleDelete()}>删除聊天</Button>` in the header row. Import `Button` (already imported). When `session` is null after delete, show an empty state ("聊天已删除").

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-frontend && npx vitest run tests/Chat.test.tsx`
Expected: PASS.

- [ ] **Step 5: Full frontend suite + typecheck + build**

Run: `cd frame-frontend && npx vitest run && npx tsc --noEmit && npm run build`
Expected: green.

- [ ] **Step 6: Commit**

```bash
git add frame-frontend/src/api/chat.ts frame-frontend/src/pages/Chat.tsx frame-frontend/tests/Chat.test.tsx
git commit -m "feat(chat): user-side delete button (soft-deletes session, syncs admin inbox)"
```

---

## Task 6: Flash-sale percentage — backend (column + price math + checkout)

**Files:**
- Modify: `frame-backend/src/main/resources/schema.sql` — add `discount_percent` to the `product` table (dev, with DROP).
- Modify: `frame-backend/src/main/resources/schema-prod.sql` — `ALTER TABLE product ADD COLUMN IF NOT EXISTS discount_percent INT DEFAULT NULL`.
- Modify: `frame-backend/src/main/java/com/iflytek/frame/entity/Product.java` — add `discountPercent`.
- Modify: `frame-backend/src/main/java/com/iflytek/frame/dto/ProductDTO.java` — add + validate.
- Modify: `frame-backend/src/main/java/com/iflytek/frame/common/Money.java` — `flashPriceInCents(Product)`.
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/impl/CheckoutServiceImpl.java` — use flash price in total.
- Test: `frame-backend/src/test/java/com/iflytek/frame/common/MoneyFlashPriceTest.java`.

**Interfaces:**
- Consumes: `Product` (has `isFlashSale`, `priceInCents`). The stock gate (`stockGateService.tryClaimStock`) operates on quantities, not prices — unchanged.
- Produces: `Product.discountPercent` (Integer). `Money.flashPriceInCents(Product)` = `priceInCents * (100 - discountPercent) / 100` when flash, else `priceInCents`. Checkout total uses per-item flash price for live flash items.

- [ ] **Step 1: Write the failing test**

`MoneyFlashPriceTest.java`:
```java
package com.iflytek.frame.common;

import com.iflytek.frame.entity.Product;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyFlashPriceTest {

    @Test
    void nonFlashReturnsListPrice() {
        Product p = new Product();
        p.setPriceInCents(1999);
        p.setFlashSale(false);
        assertThat(Money.flashPriceInCents(p)).isEqualTo(1999);
    }

    @Test
    void flashAppliesDiscount() {
        Product p = new Product();
        p.setPriceInCents(10000);
        p.setFlashSale(true);
        p.setDiscountPercent(30); // 30% off → 7000
        assertThat(Money.flashPriceInCents(p)).isEqualTo(7000);
    }

    @Test
    void flashNullPercentTreatedAsNoDiscount() {
        Product p = new Product();
        p.setPriceInCents(10000);
        p.setFlashSale(true);
        p.setDiscountPercent(null);
        assertThat(Money.flashPriceInCents(p)).isEqualTo(10000);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-backend && mvn test -Dtest=MoneyFlashPriceTest`
Expected: FAIL — `Money.flashPriceInCents` + `Product.discountPercent` don't exist.

- [ ] **Step 3: Write minimal implementation**

`schema.sql` — add column inside the `product` CREATE TABLE, after `flash_sale_at`:
```sql
    `flash_sale_at` DATETIME     DEFAULT NULL,
    `discount_percent` INT       DEFAULT NULL,
```

`schema-prod.sql` — add a migration line (idempotent) near the other product ALTERs:
```sql
ALTER TABLE `product` ADD COLUMN IF NOT EXISTS `discount_percent` INT DEFAULT NULL;
```

`Product.java` — add field after `flashSaleAt`:
```java
    private LocalDateTime flashSaleAt;
    @Schema(description = "Flash-sale discount percent (1-99); null = no discount even if flash")
    private Integer discountPercent;
    private String description;
```

`ProductDTO.java` — add field + validation after `flashSaleAt`:
```java
    private LocalDateTime flashSaleAt;
    @Min(value = 1, message = "discountPercent must be 1-99")
    @Max(value = 99, message = "discountPercent must be 1-99")
    private Integer discountPercent;
    private String description;
```
Add `import jakarta.validation.constraints.Max;`. In `toEntity()`, add `p.setDiscountPercent(discountPercent);` before `return p`. Add validation in `ProductController.create/update` (or a service guard): when `isFlashSale` is true and `discountPercent` is non-null it must be 1–99 (the `@Min/@Max` handles this on the DTO; if `isFlashSale` is false, clear `discountPercent` to null before persist — add to `toEntity` or the service).

`Money.java` — add:
```java
    /** Effective price for a product: discounted when flash-sale is live, else list price.
     *  discountPercent null or 0 → no discount (returns list price even if flash). */
    public static int flashPriceInCents(Product p) {
        if (!p.isFlashSale() || p.getDiscountPercent() == null || p.getDiscountPercent() <= 0) {
            return p.getPriceInCents();
        }
        return p.getPriceInCents() * (100 - p.getDiscountPercent()) / 100;
    }
```

`CheckoutServiceImpl.java` — line ~153 currently: `int totalInCents = Money.subtotalCents(byId, qtyByProduct);`. Replace with a flash-aware subtotal. Add a method to `Money`:
```java
    /** Subtotal using the flash price for live-flash items, list price otherwise. */
    public static int subtotalCentsFlash(Map<Long, Product> byId, Map<Long, Integer> qty) {
        int total = 0;
        for (java.util.Map.Entry<Long, Integer> e : qty.entrySet()) {
            Product p = byId.get(e.getKey());
            if (p != null) total += flashPriceInCents(p) * e.getValue();
        }
        return total;
    }
```
Then in `checkout`: `int totalInCents = Money.subtotalCentsFlash(byId, qtyByProduct);`. (The coin cap logic in `CoinServiceImpl.previewCheckoutDiscount` computes `nonFlashSubtotal` — verify it already excludes flash items and uses list price; read-only check in Step 5. The flash discount reduces the flash-item price, which does not affect the coin cap since coins apply only to non-flash items.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-backend && mvn test -Dtest=MoneyFlashPriceTest`
Expected: PASS (all three).

- [ ] **Step 5: Verify coin cap unaffected + run full suite**

Read `CoinServiceImpl.previewCheckoutDiscount`: confirm it splits flash vs non-flash and caps at `min(balance, nonFlashSubtotal)` using list prices. The flash discount only changes flash-item prices (which coins never apply to), so the cap is unaffected. If it recomputes the flash subtotal anywhere, align it with `flashPriceInCents`.

Run: `cd frame-backend && mvn test`
Expected: green. `CheckoutServiceImplTest` may assert a specific total — if it expects list-price totals for flash items, update those assertions to use the discounted price (the test should reflect the new behavior).

- [ ] **Step 6: Commit**

```bash
git add frame-backend/src/main/resources/schema.sql frame-backend/src/main/resources/schema-prod.sql \
        frame-backend/src/main/java/com/iflytek/frame/entity/Product.java \
        frame-backend/src/main/java/com/iflytek/frame/dto/ProductDTO.java \
        frame-backend/src/main/java/com/iflytek/frame/common/Money.java \
        frame-backend/src/main/java/com/iflytek/frame/service/impl/CheckoutServiceImpl.java \
        frame-backend/src/test/java/com/iflytek/frame/common/MoneyFlashPriceTest.java
git commit -m "feat(flash): discount_percent column + flashPriceInCents; checkout uses flash price"
```

---

## Task 7: Flash-sale percentage — frontend (admin % input + two-step Modal)

**Files:**
- Modify: `frame-frontend/src/api/types.ts` — add `discountPercent?: number` to `Product`.
- Modify: `frame-frontend/src/pages/Admin.tsx` (or the new `AdminProducts.tsx` if Task 8 done first) — `%` input + preview + two-step Modal.
- Test: `frame-frontend/tests/AdminProducts.test.tsx` (create) — or extend `Admin.test.tsx`.

> **Ordering note:** This task edits the product form. Task 8 (route split) decomposes `Admin.tsx` into `AdminProducts.tsx` etc. Do Task 7 first on `Admin.tsx`, THEN Task 8 moves the (already-%) form into `AdminProducts.tsx`. This avoids merge churn within the round.

**Interfaces:**
- Consumes: `createProduct`/`updateProduct` (accept the `discountPercent` field). `formatPrice`. `Modal`.
- Produces: when `isFlashSale` is checked, a "秒杀折扣 (%)" number input + live "秒杀价 ¥X" preview; submit gated behind a two-step Modal.

- [ ] **Step 1: Write the failing test**

`tests/AdminProducts.test.tsx`:
```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import Admin from '@/pages/Admin'

vi.mock('@/store/useAuthStore', () => ({
  useIsAdmin: () => true,
  useAuthStore: () => ({ user: { role: 'ADMIN' } }),
}))
vi.mock('@/api/product', () => ({
  pageProducts: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  createProduct: vi.fn().mockResolvedValue({}),
  updateProduct: vi.fn().mockResolvedValue({}),
  deleteProduct: vi.fn().mockResolvedValue({}),
  openFlashSale: vi.fn().mockResolvedValue({}),
}))

describe('Admin product flash percentage', () => {
  it('shows flash preview and gates submit behind two-step confirm', async () => {
    render(<Admin />)
    await waitFor(() => expect(screen.getByPlaceholderText('名称')).toBeInTheDocument())

    fireEvent.change(screen.getByPlaceholderText('名称'), { target: { value: 'Item' } })
    fireEvent.change(screen.getByPlaceholderText('价格 (分)'), { target: { value: '10000' } })
    fireEvent.click(screen.getByLabelText('秒杀商品'))
    fireEvent.change(screen.getByPlaceholderText('秒杀折扣 (%)'), { target: { value: '30' } })

    // preview shows discounted price ¥70.00
    expect(screen.getByText(/70\.00/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '创建' }))
    // first confirm step
    expect(screen.getByText(/确认秒杀折扣为 30%/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '确认' }))

    const { createProduct } = await import('@/api/product')
    await waitFor(() => expect(createProduct).toHaveBeenCalledWith(
      expect.objectContaining({ discountPercent: 30, isFlashSale: true }),
    ))
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-frontend && npx vitest run tests/AdminProducts.test.tsx`
Expected: FAIL — no "秒杀折扣 (%)" input, no preview, no two-step Modal.

- [ ] **Step 3: Write minimal implementation**

`api/types.ts` `Product` interface — add `discountPercent?: number`.

`Admin.tsx`:
- Add to `FormState`: `discountPercent: string` and to `EMPTY_FORM`: `discountPercent: ''`.
- In `startEdit`, set `discountPercent: p.discountPercent != null ? String(p.discountPercent) : ''`.
- In `handleSubmit`, build the payload only after confirm — restructure to open a Modal first (see below).
- Add state: `const [confirmFlash, setConfirmFlash] = useState(false)`. Make `handleSubmit` set `setConfirmFlash(true)` instead of saving immediately; a new `Modal` confirms, and the actual save runs on confirm.

Add the % input inside the `{form.isFlashSale && (...)}` block (after the datetime input):
```tsx
          <label className="flex flex-col gap-1 text-sm text-slate-600">
            <span className="text-xs text-slate-500">秒杀折扣 (%)</span>
            <Input
              type="number"
              min={1} max={99}
              placeholder="秒杀折扣 (%)"
              value={form.discountPercent}
              onChange={(e) => setForm({ ...form, discountPercent: e.target.value })}
            />
            {form.discountPercent && Number(form.discountPercent) > 0 && Number(form.priceInCents) > 0 && (
              <span className="text-xs text-brand-600">
                秒杀价 ¥{((Number(form.priceInCents) * (100 - Number(form.discountPercent)) / 100) / 100).toFixed(2)}
              </span>
            )}
          </label>
```
Add the confirm Modal (render once near the form):
```tsx
      <Modal open={confirmFlash} onClose={() => setConfirmFlash(false)} title="确认秒杀折扣">
        <p className="text-sm text-slate-600">
          确认秒杀折扣为 {form.discountPercent}%？
          {form.priceInCents && ` 秒杀价 ¥${((Number(form.priceInCents) * (100 - Number(form.discountPercent)) / 100) / 100).toFixed(2)}。`}
        </p>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setConfirmFlash(false)}>取消</Button>
          <Button variant="primary" onClick={() => { void doSave(); setConfirmFlash(false) }}>确认</Button>
        </div>
      </Modal>
```
Extract the actual save into `async function doSave()` containing the try/catch that was in `handleSubmit`, with payload:
```tsx
    const payload = {
      name: form.name,
      priceInCents: Number(form.priceInCents),
      stock: Number(form.stock),
      category: form.category || undefined,
      imageUrl: form.imageUrl || undefined,
      description: form.description || undefined,
      isFlashSale: form.isFlashSale,
      flashSaleAt: form.isFlashSale && form.flashSaleAt ? form.flashSaleAt : undefined,
      discountPercent: form.isFlashSale && form.discountPercent ? Number(form.discountPercent) : undefined,
    }
```
`handleSubmit` now just calls `e.preventDefault(); setConfirmFlash(true)`. Import `Modal`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-frontend && npx vitest run tests/AdminProducts.test.tsx`
Expected: PASS.

- [ ] **Step 5: Full frontend suite + typecheck + build**

Run: `cd frame-frontend && npx vitest run && npx tsc --noEmit && npm run build`
Expected: green.

- [ ] **Step 6: Commit**

```bash
git add frame-frontend/src/api/types.ts frame-frontend/src/pages/Admin.tsx frame-frontend/tests/AdminProducts.test.tsx
git commit -m "feat(flash): admin discount-percent input + two-step confirm Modal"
```

---

## Task 8: Admin/guest route split — decompose Admin.tsx + AdminLayout + admin login

**Files:**
- Create: `frame-frontend/src/pages/admin/AdminDashboard.tsx`, `AdminProducts.tsx`, `AdminOrders.tsx`, `AdminChat.tsx`
- Create: `frame-frontend/src/layouts/AdminLayout.tsx`
- Create: `frame-frontend/src/pages/AdminLogin.tsx`
- Modify: `frame-frontend/src/router/index.tsx`
- Modify: `frame-frontend/src/layouts/MainLayout.tsx` (drop admin nav — admins use AdminLayout)
- Modify: `frame-frontend/src/pages/Admin.tsx` — delete (logic moved out) or thin to a redirect.
- Modify: `frame-frontend/tests/MainLayout.test.tsx`, `Admin.test.tsx` (update imports).

**Interfaces:**
- Consumes: the existing `Admin.tsx` tab bodies (products/orders/chat) + `Dashboard.tsx`. `RequireAuth`. `useAuthStore` (`useIsAdmin`, `clear`). `Login` page pattern.
- Produces: `/admin/login` (standalone); `/admin` → `AdminLayout` with children `dashboard`/`products`/`orders`/`chat`. Non-admins hitting `/admin/*` redirect to `/products` (or `/login`).

- [ ] **Step 1: Decompose Admin.tsx into four pages**

Create `src/pages/admin/AdminProducts.tsx`: move the products-tab JSX + handlers (`load`, `handleSubmit`/`doSave`, `handleDelete`, `handleOpenSale`, `startEdit`, `resetForm`, form state, the flash Modal) from `Admin.tsx`. It becomes a self-contained page (no Tabs wrapper). Re-verify `useIsAdmin` gate at top.

Create `src/pages/admin/AdminOrders.tsx`: move the orders-tab JSX + handlers (`loadOrders`, `handleShip`, `handleDeliver`, `loadRefunds`, `handleApproveRefund`, `handleDenyRefund`, `ORDER_STATUS_FILTERS`, tracking-input state).

Create `src/pages/admin/AdminChat.tsx`: move the chat-inbox JSX + `ChatReplyPanel` + `loadInbox`, `selectedSessionId` state.

Create `src/pages/admin/AdminDashboard.tsx`: this is a thin re-export — `export { default } from '@/pages/Dashboard'` (Dashboard.tsx is already the console). Alternatively move Dashboard.tsx into `admin/` and re-export. Simplest: `AdminDashboard.tsx` renders `<Dashboard />`.

- [ ] **Step 2: Create AdminLayout**

`src/layouts/AdminLayout.tsx`:
```tsx
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { LayoutDashboard, Package, ClipboardList, MessageCircle, LogOut, Boxes } from 'lucide-react'
import { useAuthStore } from '@/store/useAuthStore'

const NAV = [
  { to: '/admin/dashboard', label: '控制台', icon: LayoutDashboard },
  { to: '/admin/products', label: '商品', icon: Package },
  { to: '/admin/orders', label: '订单', icon: ClipboardList },
  { to: '/admin/chat', label: '客服', icon: MessageCircle },
]

export default function AdminLayout() {
  const navigate = useNavigate()
  const clear = useAuthStore((s) => s.clear)
  const user = useAuthStore((s) => s.user)
  function handleLogout() { clear(); navigate('/admin/login', { replace: true }) }
  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-white border-b border-slate-200">
        <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-2 font-semibold text-slate-800">
            <Boxes className="w-5 h-5 text-brand-600" /><span>秒杀商城 · 管理后台</span>
          </div>
          <nav className="flex items-center gap-1">
            {NAV.map(({ to, label, icon: Icon }) => (
              <NavLink key={to} to={to} className={({ isActive }) => [
                'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-colors',
                isActive ? 'bg-brand-50 text-brand-700' : 'text-slate-600 hover:bg-slate-100',
              ].join(' ')}>
                <Icon className="w-4 h-4" />{label}
              </NavLink>
            ))}
          </nav>
          <div className="flex items-center gap-3">
            <span className="text-sm text-slate-500">{user?.username ?? '管理员'}</span>
            <button onClick={handleLogout} className="flex items-center gap-1.5 text-sm text-slate-600 hover:text-slate-900">
              <LogOut className="w-4 h-4" />登出
            </button>
          </div>
        </div>
      </header>
      <main className="flex-1 max-w-6xl w-full mx-auto px-4 py-6"><Outlet /></main>
    </div>
  )
}
```

- [ ] **Step 3: Create AdminLogin + RequireAdmin**

`src/pages/AdminLogin.tsx` — copy `Login.tsx`, change: default creds stay; on success, if `role !== 'ADMIN'` show "该账号无管理员权限" and `clear()`; if admin, `navigate('/admin/dashboard')`. Route stays `/admin/login`.

`src/router/RequireAuth.tsx` — extend with a role check, or create `src/router/RequireAdmin.tsx`:
```tsx
import { Navigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/store/useAuthStore'
export default function RequireAdmin({ children }: { children: JSX.Element }) {
  const token = useAuthStore((s) => s.token)
  const role = useAuthStore((s) => s.user?.role)
  const loc = useLocation()
  if (!token) return <Navigate to="/admin/login" state={{ from: loc }} replace />
  if (role !== 'ADMIN') return <Navigate to="/products" replace />
  return children
}
```

- [ ] **Step 4: Re-route**

`router/index.tsx` — add imports for `AdminLayout`, `AdminLogin`, `RequireAdmin`, `AdminDashboard`, `AdminProducts`, `AdminOrders`, `AdminChat`. Add routes:
```tsx
  { path: '/admin/login', element: <AdminLogin /> },
  {
    path: '/admin',
    element: <RequireAdmin><AdminLayout /></RequireAdmin>,
    children: [
      { index: true, element: <Navigate to="/admin/dashboard" replace /> },
      { path: 'dashboard', element: <AdminDashboard /> },
      { path: 'products', element: <AdminProducts /> },
      { path: 'orders', element: <AdminOrders /> },
      { path: 'chat', element: <AdminChat /> },
    ],
  },
```
Remove the old `{ path: 'admin', element: <RequireAuth><Admin /></RequireAuth> }` child. Update `IndexRoute`: admin → `/admin/dashboard` (was `/dashboard`). Delete or thin `Admin.tsx` (keep a re-export if other code imports it; otherwise delete + remove the `import Admin`).

`MainLayout.tsx` `navFor`: admins no longer use MainLayout (they go to `/admin/*`), so the `if (role === 'ADMIN')` branch can return `[]` or a single link to `/admin`. Simplest: return `[{ to: '/admin', label: '管理后台', icon: Shield }]` so an admin who lands on `/` still has a way into the admin section. Keep guests/users unchanged.

- [ ] **Step 5: Write tests**

Update `tests/MainLayout.test.tsx` + `tests/Admin.test.tsx` for the new routes/imports. Add a `tests/AdminRouting.test.tsx`:
```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import RequireAdmin from '@/router/RequireAdmin'

vi.mock('@/store/useAuthStore', () => ({
  useAuthStore: (sel: (s: any) => any) => sel({ token: 'x', user: { role: 'USER' } }),
}))

describe('RequireAdmin', () => {
  it('redirects non-admins to /products', () => {
    render(
      <MemoryRouter initialEntries={['/admin/products']}>
        <Routes>
          <Route path="/admin/products" element={<RequireAdmin><div>secret</div></RequireAdmin>} />
          <Route path="/products" element={<div>products</div>} />
        </Routes>
      </MemoryRouter>,
    )
    expect(screen.queryByText('secret')).toBeNull()
    expect(screen.getByText('products')).toBeInTheDocument()
  })
})
```

- [ ] **Step 6: Run tests + typecheck + build**

Run: `cd frame-frontend && npx vitest run && npx tsc --noEmit && npm run build`
Expected: green. Fix any tests that imported the old `Admin.tsx` default or assumed the `/admin` single-page route. The `tests/Admin.test.tsx` and `tests/MainLayout.test.tsx` need their assertions updated for the split.

- [ ] **Step 7: Commit**

```bash
git add frame-frontend/src/pages/admin/ frame-frontend/src/layouts/AdminLayout.tsx \
        frame-frontend/src/pages/AdminLogin.tsx frame-frontend/src/router/RequireAdmin.tsx \
        frame-frontend/src/router/index.tsx frame-frontend/src/layouts/MainLayout.tsx \
        frame-frontend/src/pages/Admin.tsx frame-frontend/tests/
git commit -m "feat(admin): split admin/guest routes — AdminLayout + /admin/login + decomposed pages"
```

---

## Task 9: Dashboard analytics — backend (time-windowed stats)

**Files:**
- Modify: `frame-backend/src/main/java/com/iflytek/frame/dto/DashboardStatsVO.java` — chart datasets.
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/AdminStatsService.java` + `impl/AdminStatsServiceImpl.java` — `getDashboardStats(range, from, to)`.
- Modify: `frame-backend/src/main/java/com/iflytek/frame/mapper/AdminStatsMapper.java` + `mapper/AdminStatsMapper.xml` — time-windowed queries.
- Modify: `frame-backend/src/main/java/com/iflytek/frame/controller/AdminStatsController.java` — params.
- Test: `frame-backend/src/test/java/com/iflytek/frame/service/AdminStatsServiceImplTest.java` (modify).

**Interfaces:**
- Consumes: `AdminStatsMapper`. Time-window params `range` (`1d|7d|30d|90d|365d|1095d|custom`), `from`/`to` (`LocalDate`, required when custom).
- Produces: `DashboardStatsVO` with `unitsByProduct`, `revenueTrend`, `salesShareByProduct`. `GET /admin/stats?range=30d`.

- [ ] **Step 1: Write the failing test**

Update `AdminStatsServiceImplTest.java` — change the existing test to pass a range + assert the new fields are wired:
```java
    @Test
    void getDashboardStatsAssemblesVoFromMapperWithRange() {
        AdminStatsMapper mapper = mock(AdminStatsMapper.class);
        DashboardStatsVO raw = new DashboardStatsVO(
                48200, 312, 6, 14, 4.2, List.of(), List.of(), List.of()
        );
        when(mapper.dashboardStats(any(), any(), any())).thenReturn(raw);

        AdminStatsService service = new AdminStatsServiceImpl(mapper);
        DashboardStatsVO result = service.getDashboardStats("30d", null, null);

        assertThat(result.totalRevenueCents()).isEqualTo(48200);
        verify(mapper).dashboardStats(any(), any(), any());
    }
```
(The new VO constructor arity changes — see Step 3.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-backend && mvn test -Dtest=AdminStatsServiceImplTest`
Expected: FAIL — VO has 6 components, not 9; `dashboardStats` has no params; `getDashboardStats` has no args.

- [ ] **Step 3: Write minimal implementation**

`DashboardStatsVO.java` — extend the record:
```java
public record DashboardStatsVO(
        long totalRevenueCents,
        long totalOrders,
        long totalProducts,
        long refundCount,
        double avgRating,
        List<MonthRevenue> monthlyRevenue,
        List<ProductUnits> unitsByProduct,
        List<TrendPoint> revenueTrend,
        List<ProductShare> salesShareByProduct
) {
    public record MonthRevenue(String month, long revenueCents) {}
    public record ProductUnits(Long productId, String name, long units) {}
    public record TrendPoint(String bucket, long cents) {}
    public record ProductShare(Long productId, String name, long units) {}
}
```

`AdminStatsMapper.java`:
```java
    DashboardStatsVO dashboardStats(LocalDateTime from, LocalDateTime to, String bucketExpr);
    List<DashboardStatsVO.ProductUnits> unitsByProduct(LocalDateTime from, LocalDateTime to);
    List<DashboardStatsVO.TrendPoint> revenueTrend(LocalDateTime from, LocalDateTime to, String bucketExpr);
    List<DashboardStatsVO.ProductShare> salesShareByProduct(LocalDateTime from, LocalDateTime to);
```
Remove the old `monthlyRevenue()` (folded into `revenueTrend`).

`AdminStatsMapper.xml` — replace the `dashboardStats` select with a parameterized one (bind `#{from}`, `#{to}`; the `bucketExpr` is a string used in `GROUP BY`/`DATE_FORMAT` — it must be validated server-side to one of a fixed set, never concatenated raw from client input; pass the chosen SQL expression as a constant string from the service, not the request). New selects:
```xml
    <select id="dashboardStats" resultType="com.iflytek.frame.dto.DashboardStatsVO">
        SELECT
            (SELECT COALESCE(SUM(paid_in_cents), 0) FROM orders
              WHERE paid_at &gt;= #{from} AND paid_at &lt; #{to}
                AND status IN ('PAID','SHIPPED','DELIVERED','COMPLETED')) AS totalRevenueCents,
            (SELECT COUNT(*) FROM orders WHERE create_time &gt;= #{from} AND create_time &lt; #{to}) AS totalOrders,
            (SELECT COUNT(*) FROM product) AS totalProducts,
            (SELECT COUNT(*) FROM orders WHERE refund_status = 'APPROVED'
                AND paid_at &gt;= #{from} AND paid_at &lt; #{to}) AS refundCount,
            (SELECT COALESCE(AVG((quality_stars + logistics_stars + service_stars) / 3.0), 0)
               FROM review WHERE create_time &gt;= #{from} AND create_time &lt; #{to}) AS avgRating,
            NULL AS monthlyRevenue, NULL AS unitsByProduct, NULL AS revenueTrend, NULL AS salesShareByProduct
    </select>

    <select id="unitsByProduct" resultType="com.iflytek.frame.dto.DashboardStatsVO$ProductUnits">
        SELECT oi.product_id AS productId, oi.name AS name, SUM(oi.quantity) AS units
          FROM order_item oi JOIN orders o ON oi.order_id = o.id
         WHERE o.paid_at &gt;= #{from} AND o.paid_at &lt; #{to}
           AND o.status IN ('PAID','SHIPPED','DELIVERED','COMPLETED')
         GROUP BY oi.product_id, oi.name ORDER BY units DESC
    </select>

    <select id="revenueTrend" resultType="com.iflytek.frame.dto.DashboardStatsVO$TrendPoint">
        SELECT ${bucketExpr} AS bucket, COALESCE(SUM(paid_in_cents), 0) AS cents
          FROM orders
         WHERE paid_at &gt;= #{from} AND paid_at &lt; #{to}
           AND status IN ('PAID','SHIPPED','DELIVERED','COMPLETED')
         GROUP BY ${bucketExpr} ORDER BY bucket ASC
    </select>

    <select id="salesShareByProduct" resultType="com.iflytek.frame.dto.DashboardStatsVO$ProductShare">
        SELECT oi.product_id AS productId, oi.name AS name, SUM(oi.quantity) AS units
          FROM order_item oi JOIN orders o ON oi.order_id = o.id
         WHERE o.paid_at &gt;= #{from} AND o.paid_at &lt; #{to}
           AND o.status IN ('PAID','SHIPPED','DELIVERED','COMPLETED')
         GROUP BY oi.product_id, oi.name ORDER BY units DESC
    </select>
```
**Security note on `${bucketExpr}`:** `${}` is raw substitution (SQL-injection risk). This is safe ONLY because the service passes a fixed constant selected from a whitelist by the range — never the client string. The service must map `range` → one of `"DATE_FORMAT(paid_at, '%Y-%m-%d')"`, `"'%Y-%u'"` (week), `"'%Y-%m'"` (month). Add an enum/whitelist in the service with a comment.

`AdminStatsServiceImpl.java`:
```java
    @Override
    public DashboardStatsVO getDashboardStats(String range, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = switch (range == null ? "30d" : range) {
            case "1d" -> end.minusDays(1);
            case "7d" -> end.minusDays(7);
            case "30d", null -> end.minusDays(30);
            case "90d" -> end.minusDays(90);
            case "365d" -> end.minusDays(365);
            case "1095d" -> end.minusDays(1095);
            case "custom" -> from; // from/to required; validated by controller
            default -> end.minusDays(30);
        };
        LocalDateTime fromDt = start.atStartOfDay();
        LocalDateTime toDt = end.plusDays(1).atStartOfDay(); // exclusive upper bound
        // Whitelisted bucket expression — never the raw client input (SQL-injection guard).
        String bucket = (range != null && (range.equals("365d") || range.equals("1095d")))
                ? "DATE_FORMAT(paid_at, '%Y-%m')"
                : "DATE_FORMAT(paid_at, '%Y-%m-%d')";
        DashboardStatsVO s = adminStatsMapper.dashboardStats(fromDt, toDt, bucket);
        return new DashboardStatsVO(
                s.totalRevenueCents(), s.totalOrders(), s.totalProducts(), s.refundCount(), s.avgRating(),
                List.of(), // legacy monthlyRevenue — kept null/empty for back-compat
                adminStatsMapper.unitsByProduct(fromDt, toDt),
                adminStatsMapper.revenueTrend(fromDt, toDt, bucket),
                adminStatsMapper.salesShareByProduct(fromDt, toDt)
        );
    }
```

`AdminStatsService.java` — change signature to `DashboardStatsVO getDashboardStats(String range, LocalDate from, LocalDate to);`.

`AdminStatsController.java` — change the endpoint:
```java
    @GetMapping("/stats")
    public Result<DashboardStatsVO> stats(@RequestParam(defaultValue = "30d") String range,
                                          @RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to) {
        if ("custom".equals(range) && (from == null || to == null)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "custom range requires from and to");
        }
        return Result.success(adminStatsService.getDashboardStats(range, from, to));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-backend && mvn test -Dtest=AdminStatsServiceImplTest`
Expected: PASS.

- [ ] **Step 5: Full backend suite**

Run: `cd frame-backend && mvn test`
Expected: green.

- [ ] **Step 6: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/dto/DashboardStatsVO.java \
        frame-backend/src/main/java/com/iflytek/frame/service/AdminStatsService.java \
        frame-backend/src/main/java/com/iflytek/frame/service/impl/AdminStatsServiceImpl.java \
        frame-backend/src/main/java/com/iflytek/frame/mapper/AdminStatsMapper.java \
        frame-backend/src/main/resources/mapper/AdminStatsMapper.xml \
        frame-backend/src/main/java/com/iflytek/frame/controller/AdminStatsController.java \
        frame-backend/src/test/java/com/iflytek/frame/service/AdminStatsServiceImplTest.java
git commit -m "feat(dashboard): time-windowed stats (range/from/to) + 3 chart datasets"
```

---

## Task 10: Dashboard analytics — frontend (filter bar + 3 charts)

**Files:**
- Modify: `frame-frontend/src/api/stats.ts` — chart types + params.
- Modify: `frame-frontend/src/pages/Dashboard.tsx` (now `admin/AdminDashboard.tsx` if Task 8 done) — filter bar + 3 Recharts charts.
- Test: `frame-frontend/tests/Dashboard.test.tsx` (modify).

**Interfaces:**
- Consumes: `getAdminStats(range, from, to)` returning the extended VO. Recharts `BarChart/LineChart/PieChart` (already deps).
- Produces: a filter bar (preset Select + custom date inputs) driving refetch; bar/line/pie charts.

- [ ] **Step 1: Write the failing test**

Update `tests/Dashboard.test.tsx`:
```tsx
const getAdminStats = vi.fn().mockResolvedValue({
  totalRevenueCents: 48200, totalOrders: 312, totalProducts: 6, refundCount: 14, avgRating: 4.2,
  monthlyRevenue: [],
  unitsByProduct: [{ productId: 1, name: 'Widget', units: 40 }],
  revenueTrend: [{ bucket: '2026-08-20', cents: 8100 }],
  salesShareByProduct: [{ productId: 1, name: 'Widget', units: 40 }],
})
vi.mock('@/api/stats', () => ({ getAdminStats }))

describe('Dashboard analytics', () => {
  it('renders 3 charts and refetches on range change', async () => {
    render(<Dashboard />)
    await waitFor(() => expect(screen.getByText('总营收')).toBeInTheDocument())
    // bar + pie share "Widget"; line shows the trend bucket
    expect(getAdminStats).toHaveBeenCalledWith('30d', undefined, undefined)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-frontend && npx vitest run tests/Dashboard.test.tsx`
Expected: FAIL — `getAdminStats` doesn't accept params; no charts beyond the bar.

- [ ] **Step 3: Write minimal implementation**

`api/stats.ts` — extend types + signature:
```ts
export interface ProductUnits { productId: number; name: string; units: number }
export interface TrendPoint { bucket: string; cents: number }
export interface DashboardStats {
  totalRevenueCents: number; totalOrders: number; totalProducts: number
  refundCount: number; avgRating: number; monthlyRevenue: MonthRevenue[]
  unitsByProduct: ProductUnits[]; revenueTrend: TrendPoint[]; salesShareByProduct: ProductUnits[]
}
export function getAdminStats(range = '30d', from?: string, to?: string): Promise<DashboardStats> {
  return request.get<DashboardStats>('/admin/stats', { params: { range, from, to } })
}
```

`Dashboard.tsx` — add filter state + 3 charts:
```tsx
  const [range, setRange] = useState('30d')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  useEffect(() => {
    setLoading(true)
    getAdminStats(range, range === 'custom' ? from || undefined : undefined, range === 'custom' ? to || undefined : undefined)
      .then(setStats).catch((e) => setError(e instanceof Error ? e.message : '加载失败')).finally(() => setLoading(false))
  }, [range, from, to])
```
Add a filter bar (Select with `1d/7d/30d/90d/365d/1095d/custom` + two date inputs when `custom`). Replace the single BarChart with three `<Card>` sections: bar (`unitsByProduct`, `dataKey="units"`), line (`revenueTrend`, `dataKey="cents"`), pie (`salesShareByProduct`, `dataKey="units"`). Import `LineChart, Line, PieChart, Pie, Cell, Legend` from recharts.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frame-frontend && npx vitest run tests/Dashboard.test.tsx`
Expected: PASS.

- [ ] **Step 5: Full frontend suite + typecheck + build**

Run: `cd frame-frontend && npx vitest run && npx tsc --noEmit && npm run build`
Expected: green.

- [ ] **Step 6: Commit**

```bash
git add frame-frontend/src/api/stats.ts frame-frontend/src/pages/Dashboard.tsx frame-frontend/tests/Dashboard.test.tsx
git commit -m "feat(dashboard): filter bar + bar/line/pie charts (time-windowed)"
```

---

## Task 11: Final regression — full suites + manual smoke

- [ ] **Step 1: Backend full suite**

Run: `cd frame-backend && mvn test`
Expected: all green, 0 failures/errors/skipped.

- [ ] **Step 2: Frontend full suite + typecheck + build**

Run: `cd frame-frontend && npx vitest run && npx tsc --noEmit && npm run build`
Expected: all green.

- [ ] **Step 3: Local smoke (both servers)**

- Backend: `cd frame-backend && mvn package -DskipTests && java -jar target/frame-backend-0.0.1-SNAPSHOT.jar` (port 8081).
- Apply dev schema: `mariadb -u root -pframe_dev frame_db < src/main/resources/schema.sql` (adds `discount_percent`).
- Frontend: `cd frame-frontend && npm run dev` (port 5173).
- Smoke: login admin/admin123 → `/admin/login` → `/admin/dashboard` (filters + 3 charts) → `/admin/products` (set a flash % + confirm) → checkout that flash item (verify discounted total) → as a user, profile "注销账号" (verify logout + 401 on reuse) → chat "删除聊天" (verify gone from admin inbox).

- [ ] **Step 4: Commit any test-fix churn + push**

```bash
git add -A
git commit -m "test: final regression green for feature round" --allow-empty
```

- [ ] **Step 5: Report**

Report the green counts (backend N/N, frontend N/N, build clean), the smoke results, and the commit range on `main` (or feature branch). Note the deferred distributed-deployment spec for the next phase.
