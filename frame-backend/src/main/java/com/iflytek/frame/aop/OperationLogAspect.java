package com.iflytek.frame.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.entity.OperationLog;
import com.iflytek.frame.mapper.OperationLogMapper;
import com.iflytek.frame.security.SecurityUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Logs every mutating controller call (POST/PUT/DELETE) to the operation_log table
 * (NFR: 操作日志记录). Captures user/method/URI/masked-params/result-status/cost/ip.
 * Sensitive fields are redacted in the params blob before persisting.
 *
 * <p>The {@link ObjectMapper} is instantiated directly (not injected) because Spring Boot 4
 * auto-configures a Jackson 3 {@code tools.jackson.databind.ObjectMapper} bean, which is not
 * assignment-compatible with the Jackson 2 {@code com.fasterxml.jackson.databind.ObjectMapper}
 * this class uses (brought in transitively by jjwt-jackson). This mirrors the lazy-resolve +
 * {@code new ObjectMapper()} fallback already used in {@code SecurityConfig}.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);
    private final OperationLogMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Around("execution(* com.iflytek.frame.controller..*.*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        String status = "SUCCESS";
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            status = "FAIL";
            throw ex;
        } finally {
            try {
                writeLog(pjp, status, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.warn("Failed to write operation log: {}", e.getMessage());
            }
        }
        return result;
    }

    private void writeLog(ProceedingJoinPoint pjp, String status, long costMs) {
        HttpServletRequest req = currentRequest();
        if (req == null) return;
        String method = req.getMethod();
        // Only log mutating methods to keep volume sane.
        if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)) return;

        OperationLog entry = new OperationLog();
        entry.setMethod(method);
        entry.setUri(req.getRequestURI());
        entry.setResultStatus(status);
        entry.setCostMs((int) costMs);
        entry.setIp(clientIp(req));

        SecurityUser su = currentUserOrNull();
        if (su != null) {
            entry.setUserId(su.userId());
            entry.setUsername(su.username());
        }
        entry.setParams(maskedArgs(pjp.getArgs()));
        mapper.insert(entry);
    }

    static String maskSensitive(String json) {
        if (json == null) return null;
        // Redact known sensitive fields' string values to ***.
        for (String f : new String[]{"password", "oldPassword", "newPassword",
                "code", "captchaAnswer", "accessKeySecret", "accessKeySecret"}) {
            json = json.replaceAll("(?i)\"" + f + "\"\\s*:\\s*\"[^\"]*\"", "\"" + f + "\":\"***\"");
        }
        return json;
    }

    private String maskedArgs(Object[] args) {
        try {
            if (args == null || args.length == 0) return null;
            // Serialize the first non-null arg (the @RequestBody) — skip MultipartFile.
            for (Object a : args) {
                if (a == null) continue;
                if (a instanceof org.springframework.web.multipart.MultipartFile) return "[multipart]";
                return maskSensitive(objectMapper.writeValueAsString(a));
            }
            return null;
        } catch (Exception e) {
            return "[unserializable]";
        }
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private SecurityUser currentUserOrNull() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof SecurityUser su) return su;
        } catch (Exception ignored) { }
        return null;
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
