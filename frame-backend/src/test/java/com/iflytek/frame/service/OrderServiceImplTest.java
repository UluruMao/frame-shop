package com.iflytek.frame.service;

import com.iflytek.frame.entity.Order;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for the correlation-id→PK resolution in
 * {@link OrderServiceImpl#getById} / {@link OrderServiceImpl#cancel}.
 *
 * <p>The checkout hot path returns a millis-based correlation id, NOT the DB PK. After the
 * consumer durably persists the order, it writes {@code order_id_map:{correlationId} = <dbPk>}
 * to Redis. {@code getById} resolves the correlation id → real PK via that map, then loads
 * the Order by the PK and applies the ownership check.
 *
 * <p>These tests verify the three resolution outcomes:
 * <ul>
 *   <li>Correlation id found in the map → resolve to real PK → return the order.</li>
 *   <li>Real PK passed directly → fast-path {@code selectById(id)} returns the order.</li>
 *   <li>Neither a real PK nor a mapped correlation id → NOT_FOUND.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock OrderMapper orderMapper;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(orderMapper, redis);
    }

    @Test
    void getById_correlationId_resolvesViaMapAndReturnsOrder() {
        long corrId = 1690000000000L; // what the client holds (from the 202 response)
        long realPk = 42L;           // DB auto-generated PK

        // selectById(corrId) returns null (corrId is NOT the PK) → slow path
        when(orderMapper.selectById(corrId)).thenReturn(null);
        // Redis map: order_id_map:{corrId} = "42"
        when(redis.opsForValue()).thenReturn(valueOps);
        // getById checks order_status:{id} for the queued case first; not "queued" here
        when(valueOps.get("order_status:" + corrId)).thenReturn(null);
        when(valueOps.get("order_id_map:" + corrId)).thenReturn(String.valueOf(realPk));
        // selectById(realPk) to validate + load the order
        Order order = new Order();
        order.setId(realPk);
        order.setUserId(7L);
        when(orderMapper.selectById(realPk)).thenReturn(order);

        Order result = orderService.getById(corrId, 7L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(realPk);
        // fast-path selectById(corrId) was tried first (returned null → slow path)
        verify(orderMapper).selectById(corrId);
        // then the map was consulted
        verify(valueOps).get("order_id_map:" + corrId);
        // selectById(realPk) called twice: once in resolveOrderId (validates the map
        // points to a real PK), once in getById (loads the order for ownership check).
        // The validation ensures a stale/corrupt map entry → NOT_FOUND (not a misleading
        // downstream error); getById's own load applies the ownership check.
        verify(orderMapper, times(2)).selectById(realPk);
    }

    @Test
    void getById_realPkPassedDirectly_fastPathsAndReturnsOrder() {
        long realPk = 42L;

        // selectById(realPk) returns the order directly (fast path)
        Order order = new Order();
        order.setId(realPk);
        order.setUserId(7L);
        when(orderMapper.selectById(realPk)).thenReturn(order);
        // getById checks order_status:{id} for the queued case first; not "queued" here
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("order_status:" + realPk)).thenReturn(null);

        Order result = orderService.getById(realPk, 7L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(realPk);
        // The order_id_map is NOT consulted on the fast path (only order_status was)
        verify(valueOps, never()).get("order_id_map:" + realPk);
    }

    @Test
    void getById_correlationIdNotInMap_throwsNotFound() {
        long corrId = 1690000000000L;

        when(orderMapper.selectById(corrId)).thenReturn(null); // not a real PK
        when(redis.opsForValue()).thenReturn(valueOps);
        // getById checks order_status:{id} for the queued case first; not "queued" here
        when(valueOps.get("order_status:" + corrId)).thenReturn(null);
        when(valueOps.get("order_id_map:" + corrId)).thenReturn(null); // not in map either

        assertThatThrownBy(() -> orderService.getById(corrId, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("order not found");
    }

    @Test
    void getById_resolvedOrderWrongUser_throwsNotFound() {
        long corrId = 1690000000000L;
        long realPk = 42L;

        when(orderMapper.selectById(corrId)).thenReturn(null);
        when(redis.opsForValue()).thenReturn(valueOps);
        // getById checks order_status:{id} for the queued case first; not "queued" here
        when(valueOps.get("order_status:" + corrId)).thenReturn(null);
        when(valueOps.get("order_id_map:" + corrId)).thenReturn(String.valueOf(realPk));
        // order belongs to user 99, not 7
        Order order = new Order();
        order.setId(realPk);
        order.setUserId(99L);
        when(orderMapper.selectById(realPk)).thenReturn(order);

        assertThatThrownBy(() -> orderService.getById(corrId, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("order not found");
    }

    @Test
    void getById_queuedInRedis_returnsSyntheticOrderForPolling() {
        long corrId = 1690000000000L; // what the client holds (from the 202 response)

        // order_status:{corrId} = "queued" — consumer hasn't persisted the row yet
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("order_status:" + corrId)).thenReturn("queued");

        Order result = orderService.getById(corrId, 7L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(corrId);
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING); // placeholder
        assertThat(result.getUserId()).isEqualTo(7L);
        // The DB must NOT be hit — the order row doesn't exist yet
        verify(orderMapper, never()).selectById(any());
        // The order_id_map must NOT be consulted — queued means no PK mapping yet
        verify(valueOps, never()).get("order_id_map:" + corrId);
    }
}
