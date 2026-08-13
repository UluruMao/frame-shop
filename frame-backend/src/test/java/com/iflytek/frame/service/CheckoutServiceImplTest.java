package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;
import com.iflytek.frame.dto.OrderItemDTO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.impl.CheckoutServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceImplTest {
    @Mock ProductMapper productMapper;
    @Mock OrderMapper orderMapper;
    @Mock OrderItemMapper orderItemMapper;
    @InjectMocks CheckoutServiceImpl checkoutService;

    private CheckoutRequest req(String key) {
        CheckoutRequest r = new CheckoutRequest();
        OrderItemDTO item = new OrderItemDTO();
        item.setProductId(1L); item.setQuantity(2);
        r.setItems(List.of(item));
        r.setCustomerName("Bob"); r.setCustomerEmail("b@x.com"); r.setShippingAddress("here");
        r.setIdempotencyKey(key);
        return r;
    }

    @Test
    void checkout_stockSufficient_decrementsAndCreatesPendingOrder() {
        Product p = new Product(); p.setId(1L); p.setPriceInCents(1999); p.setStock(5);
        when(productMapper.selectById(1L)).thenReturn(p);
        when(productMapper.decrementStock(1L, 2)).thenReturn(1); // stock claimed
        when(orderMapper.insert(any(com.iflytek.frame.entity.Order.class))).thenAnswer(inv -> { ((com.iflytek.frame.entity.Order) inv.getArgument(0)).setId(42L); return 1; });

        CheckoutResponse resp = checkoutService.checkout(req("key-1"), 7L);

        assertThat(resp.getOrderId()).isEqualTo(42L);
        assertThat(resp.getStatus()).isEqualTo("pending");
        verify(productMapper).decrementStock(1L, 2);
        verify(orderMapper).insert(any(com.iflytek.frame.entity.Order.class));
    }

    @Test
    void checkout_stockInsufficient_throwsConflict() {
        Product p = new Product(); p.setId(1L); p.setPriceInCents(1999); p.setStock(1);
        when(productMapper.selectById(1L)).thenReturn(p);
        when(productMapper.decrementStock(1L, 2)).thenReturn(0); // stock claim FAILED

        assertThatThrownBy(() -> checkoutService.checkout(req("key-2"), 7L))
                .isInstanceOf(com.iflytek.frame.exception.BusinessException.class);
        verify(orderMapper, never()).insert(any(com.iflytek.frame.entity.Order.class));
    }

    @Test
    void checkout_duplicateIdempotencyKey_returnsExistingPendingOrder() {
        Product p = new Product(); p.setId(1L); p.setPriceInCents(1999); p.setStock(5);
        when(productMapper.selectById(1L)).thenReturn(p);
        when(productMapper.decrementStock(1L, 2)).thenReturn(1);
        when(orderMapper.insert(any(com.iflytek.frame.entity.Order.class))).thenAnswer(inv -> { ((com.iflytek.frame.entity.Order) inv.getArgument(0)).setId(99L); return 1; });

        checkoutService.checkout(req("dup-key"), 7L);          // first
        // second call with same key already has a row -> DuplicateKeyException
        when(orderMapper.insert(any(com.iflytek.frame.entity.Order.class))).thenThrow(new DuplicateKeyException("uk_idempotency_key"));
        // the existing order the duplicate-key path looks up and returns
        com.iflytek.frame.entity.Order existing = new com.iflytek.frame.entity.Order();
        existing.setId(99L);
        when(orderMapper.selectByIdempotencyKey("dup-key")).thenReturn(existing);
        CheckoutResponse resp = checkoutService.checkout(req("dup-key"), 7L);

        assertThat(resp.getOrderId()).isEqualTo(99L);
        assertThat(resp.getStatus()).isEqualTo("pending");
        // stock decremented only ONCE (first call); second call's decrement must be reversed
        verify(productMapper, times(2)).decrementStock(1L, 2); // called twice, but second rolled back
        verify(productMapper).recreditStock(1L, 2);           // second call's decrement reversed
    }

    @Test
    void checkout_duplicateIdempotencyKey_existingOrderVanishes_throwsConflictNotNpe() {
        Product p = new Product(); p.setId(1L); p.setPriceInCents(1999); p.setStock(5);
        when(productMapper.selectById(1L)).thenReturn(p);
        when(productMapper.decrementStock(1L, 2)).thenReturn(1);
        // insert collides on the idempotency-key UNIQUE constraint, but the existing
        // row is gone by the time we look it up (concurrent delete / DB blip).
        when(orderMapper.insert(any(com.iflytek.frame.entity.Order.class)))
                .thenThrow(new DuplicateKeyException("uk_idempotency_key"));
        when(orderMapper.selectByIdempotencyKey("vanished-key")).thenReturn(null);

        // must surface as a BusinessException (CONFLICT), NOT a NullPointerException
        assertThatThrownBy(() -> checkoutService.checkout(req("vanished-key"), 7L))
                .isInstanceOf(com.iflytek.frame.exception.BusinessException.class);
        // the decrement we made before the insert must be reversed
        verify(productMapper).recreditStock(1L, 2);
    }
}
