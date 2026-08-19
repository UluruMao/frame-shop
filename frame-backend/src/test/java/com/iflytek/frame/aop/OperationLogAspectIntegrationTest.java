package com.iflytek.frame.aop;

import com.iflytek.frame.common.Role;
import com.iflytek.frame.controller.AdminSaleController;
import com.iflytek.frame.entity.OperationLog;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.OperationLogMapper;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.security.JwtUtil;
import com.iflytek.frame.security.SecurityUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@link OperationLogAspect} actually persists a row to the
 * {@code operation_log} table when a mutating controller call (POST/PUT/DELETE)
 * flows through the AOP proxy — i.e. the NFR 操作日志记录 works at runtime, not just
 * that the {@code maskSensitive} helper passes its unit test.
 *
 * <p>This is the regression guard for the {@code update_time} column fix: before that
 * fix, {@code OperationLog extends BaseEntity} (whose {@code updateTime} is auto-filled
 * on insert) but the table lacked {@code update_time}, so every {@code mapper.insert}
 * failed with {@code Unknown column 'update_time'} and was silently swallowed by the
 * aspect's {@code finally} try/catch — zero rows persisted. After the fix the insert
 * succeeds and a row appears.
 *
 * <p>The controller bean is injected as an AOP proxy (the aspect's {@code @Around}
 * pointcut covers {@code com.iflytek.frame.controller..*}), so calling its method
 * fires the advice. A {@link MockHttpServletRequest} + {@link RequestContextHolder}
 * stand in for the live HTTP request (the aspect reads the method/URI from there);
 * {@link SecurityContextHolder} carries the {@link SecurityUser} principal so the
 * aspect can record the user.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class OperationLogAspectIntegrationTest {

    @Autowired AdminSaleController adminSaleController;
    @Autowired JwtUtil jwtUtil;
    @Autowired OperationLogMapper operationLogMapper;
    @Autowired ProductMapper productMapper;

    @BeforeEach
    void clearContext() {
        // ensure clean request + security context for each test
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void restoreContext() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void mutatingControllerCall_persistsOperationLogRow() {
        // a product to target (POST /admin/sales/{productId} just loads its stock into Redis)
        Product p = new Product();
        p.setName("OpLog ITest Widget");
        p.setPriceInCents(100);
        p.setStock(3);
        productMapper.insert(p);

        long before = operationLogMapper.selectCount(null);

        // stand in for the live HTTP request the aspect reads method/URI from
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/admin/sales/" + p.getId());
        req.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        // the aspect reads the principal from SecurityContextHolder
        SecurityUser su = new SecurityUser(1L, "admin", Role.ADMIN, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(su, null, su.authorities()));

        try {
            // calling the injected bean goes through the AOP proxy → @Around fires → writeLog runs
            adminSaleController.openSale(p.getId());

            List<OperationLog> all = operationLogMapper.selectList(null);
            long after = all.size();
            assertThat(after).as("an operation_log row was persisted for the POST").isGreaterThan(before);

            OperationLog latest = all.get(all.size() - 1);
            assertThat(latest.getMethod()).isEqualTo("POST");
            assertThat(latest.getUri()).isEqualTo("/admin/sales/" + p.getId());
            assertThat(latest.getResultStatus()).isEqualTo("SUCCESS");
            assertThat(latest.getUserId()).isEqualTo(1L);
            assertThat(latest.getUsername()).isEqualTo("admin");
            assertThat(latest.getCostMs()).isNotNull();
        } finally {
            productMapper.deleteById(p.getId());
        }
    }
}
