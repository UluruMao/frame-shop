package com.iflytek.frame.service;

import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;
import com.iflytek.frame.dto.OrderItemDTO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.impl.CheckoutServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the async hot-path checkout (Taobao seckill model).
 *
 * <p>The hot path: aggregate → {@code selectBatchIds} (server-side price) → Redis Lua gate
 * ({@code tryClaimStock}) → compute total → XADD to {@code orders:pending} Stream → set
 * {@code order_status:{id}="queued"} → return 202. No DB writes in the hot path.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceImplTest {

    @Mock StringRedisTemplate redis;
    @Mock ProductMapper productMapper;
    @Mock StockGateService stockGateService;
    @Mock CoinService coinService;
    @Mock StreamOperations<String, String, String> streamOps;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks CheckoutServiceImpl checkoutService;

    private CheckoutRequest req(String key) {
        CheckoutRequest r = new CheckoutRequest();
        OrderItemDTO item = new OrderItemDTO();
        item.setProductId(1L);
        item.setQuantity(2);
        r.setItems(List.of(item));
        r.setCustomerName("Bob");
        r.setCustomerEmail("b@x.com");
        r.setShippingAddress("here");
        r.setCustomerPhone("13900000000");
        r.setPaymentMethod("WECHAT");
        r.setIdempotencyKey(key);
        return r;
    }

    @Test
    void checkout_nonFlashProduct_skipsRedisGate_succeedsViaDbBackstop() {
        // Flash is only a DISCOUNT, not a purchase gate. A non-flash product must be
        // buyable without the Redis Lua gate — the consumer's DB-backstop decrementStock
        // (WHERE stock >= qty) is the oversell authority. So tryClaimStock is NOT called.
        Product p = new Product();
        p.setId(1L);
        p.setPriceInCents(1999);
        p.setStock(5);
        p.setFlashSale(false);
        when(productMapper.selectBatchIds(anyCollection())).thenReturn(List.of(p));
        doReturn(streamOps).when(redis).opsForStream();
        when(redis.opsForValue()).thenReturn(valueOps);

        CheckoutResponse resp = checkoutService.checkout(req("key-noflash"), 7L);

        assertThat(resp.getStatus()).isEqualTo("queued");
        verify(stockGateService, never()).tryClaimStock(anyList(), anyList());
        verify(redis).opsForStream();
    }

    @Test
    void checkout_stockClaimed_lpushAndReturnsQueued() {
        Product p = new Product();
        p.setId(1L);
        p.setPriceInCents(1999);
        p.setStock(5);
        p.setFlashSale(true); // flash-live: Redis gate applies
        when(productMapper.selectBatchIds(anyCollection())).thenReturn(List.of(p));
        // stock:{id} exists (sale live) but seckill:{id}:{user} does NOT (not yet purchased).
        when(redis.hasKey("stock:1")).thenReturn(true);
        when(stockGateService.tryClaimStock(anyList(), anyList())).thenReturn("OK");
        doReturn(streamOps).when(redis).opsForStream();
        when(redis.opsForValue()).thenReturn(valueOps);

        CheckoutResponse resp = checkoutService.checkout(req("key-1"), 7L);

        assertThat(resp.getStatus()).isEqualTo("queued");
        assertThat(resp.getOrderId()).isNotNull();
        verify(stockGateService).tryClaimStock(List.of(1L), List.of(2));
        verify(redis).opsForStream();
        verify(valueOps).set(startsWith("order_status:"), eq("queued"));
    }

    @Test
    void checkout_soldOut_throwsConflict() {
        Product p = new Product();
        p.setId(1L);
        p.setPriceInCents(1999);
        p.setStock(1);
        p.setFlashSale(true); // flash-live: Redis gate applies
        when(productMapper.selectBatchIds(anyCollection())).thenReturn(List.of(p));
        when(redis.hasKey("stock:1")).thenReturn(true);
        when(stockGateService.tryClaimStock(anyList(), anyList())).thenReturn("FAIL");

        assertThatThrownBy(() -> checkoutService.checkout(req("key-2"), 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(409);
        verify(redis, never()).opsForStream();
    }

    @Test
    void checkout_flashNotOpen_skipsGate_succeedsAtListPrice() {
        // Flash product whose sale isn't live (stock:{id} key missing) → NOT 503; bought
        // at list price via DB-backstop (flash is only a discount, not a gate).
        Product p = new Product();
        p.setId(1L);
        p.setPriceInCents(1999);
        p.setStock(5);
        p.setFlashSale(true);
        when(productMapper.selectBatchIds(anyCollection())).thenReturn(List.of(p));
        when(redis.hasKey("stock:1")).thenReturn(false); // sale not open → dbOnly, no gate
        doReturn(streamOps).when(redis).opsForStream();
        when(redis.opsForValue()).thenReturn(valueOps);

        CheckoutResponse resp = checkoutService.checkout(req("key-2b"), 7L);

        assertThat(resp.getStatus()).isEqualTo("queued");
        verify(stockGateService, never()).tryClaimStock(anyList(), anyList());
    }

    @Test
    void checkout_productNotFound_throwsNotFound() {
        when(productMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> checkoutService.checkout(req("key-3"), 7L))
                .isInstanceOf(BusinessException.class);
        verify(stockGateService, never()).tryClaimStock(anyList(), anyList());
    }

    @Test
    void checkout_invalidPaymentMethod_throwsParamInvalid() {
        CheckoutRequest r = req("key-3b");
        r.setPaymentMethod("BITCOIN"); // not one of WECHAT/ALIPAY/BANK_CARD

        assertThatThrownBy(() -> checkoutService.checkout(r, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(1001); // PARAM_INVALID
        verify(stockGateService, never()).tryClaimStock(anyList(), anyList());
    }

    @Test
    void checkout_nullPaymentMethod_throwsParamInvalid() {
        CheckoutRequest r = req("key-3c");
        r.setPaymentMethod(null);

        assertThatThrownBy(() -> checkoutService.checkout(r, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(1001); // PARAM_INVALID
    }

    @Test
    void checkout_multiItem_aggregatesQuantities() {
        OrderItemDTO a = new OrderItemDTO();
        a.setProductId(1L);
        a.setQuantity(2);
        OrderItemDTO b = new OrderItemDTO();
        b.setProductId(1L);
        b.setQuantity(3);
        CheckoutRequest r = new CheckoutRequest();
        r.setItems(List.of(a, b));
        r.setCustomerName("Bob");
        r.setCustomerEmail("b@x.com");
        r.setShippingAddress("here");
        r.setCustomerPhone("13900000000");
        r.setPaymentMethod("ALIPAY");
        r.setIdempotencyKey("key-4");

        Product p = new Product();
        p.setId(1L);
        p.setPriceInCents(1000);
        p.setStock(10);
        p.setFlashSale(true); // flash-live: Redis gate applies
        when(productMapper.selectBatchIds(anyCollection())).thenReturn(List.of(p));
        when(redis.hasKey("stock:1")).thenReturn(true);
        when(stockGateService.tryClaimStock(anyList(), anyList())).thenReturn("OK");
        doReturn(streamOps).when(redis).opsForStream();
        when(redis.opsForValue()).thenReturn(valueOps);

        CheckoutResponse resp = checkoutService.checkout(r, 7L);

        assertThat(resp.getStatus()).isEqualTo("queued");
        verify(stockGateService).tryClaimStock(List.of(1L), List.of(5));
    }

    private static String startsWith(String prefix) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.startsWith(prefix));
    }
}
