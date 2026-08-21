package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.ReviewCreateRequest;
import com.iflytek.frame.dto.ReviewVO;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Review;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.ReviewMapper;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.service.impl.ReviewServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceImplTest {

    @Mock ReviewMapper reviewMapper;
    @Mock OrderItemMapper orderItemMapper;
    @Mock OrderService orderService;
    @Mock CoinService coinService;
    ReviewServiceImpl reviewService;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewServiceImpl(reviewMapper, orderItemMapper, orderService,
                coinService);
    }

    private Order deliveredOrder() {
        Order o = new Order();
        o.setId(7L);
        o.setUserId(1L);
        o.setStatus(OrderStatus.DELIVERED);
        return o;
    }

    private OrderItem item(long pid) {
        OrderItem it = new OrderItem();
        it.setOrderId(7L);
        it.setProductId(pid);
        it.setName("P" + pid);
        return it;
    }

    private ReviewCreateRequest req(long pid) {
        ReviewCreateRequest r = new ReviewCreateRequest();
        r.setOrderId(7L);
        r.setProductId(pid);
        r.setQualityStars(5);
        r.setLogisticsStars(4);
        r.setServiceStars(3);
        r.setContent("ok");
        r.setImageUrls(List.of("/uploads/reviews/a.jpg"));
        r.setIsAnonymous(false);
        return r;
    }

    @Test
    void create_insertsAndCompletes_whenLastItemReviewed() {
        Order o = deliveredOrder();
        when(orderService.getById(7L, 1L)).thenReturn(o);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(100L), item(200L)));
        // before insert: one item already reviewed (pid 100). This create reviews pid 200 → fully reviewed.
        Review existingFor100 = new Review();
        existingFor100.setProductId(100L);
        when(reviewMapper.selectByOrderId(7L)).thenReturn(List.of(existingFor100));
        when(reviewMapper.selectByOrderAndProduct(7L, 200L)).thenReturn(null).thenReturn(stubReview(200L));

        reviewService.create(req(200L), 1L);

        verify(reviewMapper).insert(any(Review.class));
        verify(orderService).completeOrder(7L);
        verify(coinService).awardReviewReward(7L, 1L);
    }

    @Test
    void create_doesNotComplete_whenOtherItemsUnreviewed() {
        Order o = deliveredOrder();
        when(orderService.getById(7L, 1L)).thenReturn(o);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(100L), item(200L)));
        when(reviewMapper.selectByOrderId(7L)).thenReturn(List.of()); // nothing reviewed yet
        when(reviewMapper.selectByOrderAndProduct(7L, 100L)).thenReturn(null).thenReturn(stubReview(100L));

        reviewService.create(req(100L), 1L);

        verify(orderService, never()).completeOrder(any());
        verify(coinService, never()).awardReviewReward(any(), any());
    }

    @Test
    void create_upsertsWhenAlreadyReviewed_doesNotRecomplete() {
        Order o = deliveredOrder();
        when(orderService.getById(7L, 1L)).thenReturn(o);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(100L)));
        Review existing = stubReview(100L);
        existing.setUserId(1L);
        when(reviewMapper.selectByOrderAndProduct(7L, 100L)).thenReturn(existing);

        ReviewVO vo = reviewService.create(req(100L), 1L);

        verify(reviewMapper, never()).insert(any(Review.class));
        verify(reviewMapper).updateById(any(Review.class));
        verify(orderService, never()).completeOrder(any());
        verify(coinService, never()).awardReviewReward(any(), any());
        assertThat(vo.isEdited()).isTrue();
    }

    @Test
    void create_nonDeliverableState_throwsConflict() {
        Order o = deliveredOrder();
        o.setStatus(OrderStatus.REFUNDED);
        when(orderService.getById(7L, 1L)).thenReturn(o);
        assertThatThrownBy(() -> reviewService.create(req(100L), 1L))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.CONFLICT.getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    @Test
    void create_productNotInOrder_throwsParamInvalid() {
        when(orderService.getById(7L, 1L)).thenReturn(deliveredOrder());
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(100L))); // only 100 in order
        assertThatThrownBy(() -> reviewService.create(req(999L), 1L)) // reviewing 999
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.PARAM_INVALID.getCode());
    }

    @Test
    void create_acceptsCompletedOrderWithUnreviewedItems() {
        Order o = deliveredOrder();
        o.setStatus(OrderStatus.COMPLETED);
        when(orderService.getById(7L, 1L)).thenReturn(o);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(100L)));
        when(reviewMapper.selectByOrderId(7L)).thenReturn(List.of());
        when(reviewMapper.selectByOrderAndProduct(7L, 100L)).thenReturn(null).thenReturn(stubReview(100L));

        reviewService.create(req(100L), 1L);

        // completeOrder is a no-op on an already-COMPLETED order (CAS rows==0) but we still call it.
        verify(orderService).completeOrder(7L);
        verify(coinService).awardReviewReward(7L, 1L);
    }

    private Review stubReview(long pid) {
        Review r = new Review();
        r.setId(1L);
        r.setOrderId(7L);
        r.setUserId(1L);
        r.setProductId(pid);
        r.setQualityStars(5);
        r.setLogisticsStars(4);
        r.setServiceStars(3);
        return r;
    }
}
