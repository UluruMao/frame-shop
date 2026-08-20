package com.iflytek.frame;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.dto.*;
import com.iflytek.frame.entity.*;
import com.iflytek.frame.mapper.*;
import com.iflytek.frame.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class ReviewIntegrationTest {

    @Autowired ReviewService reviewService;
    @Autowired OrderService orderService;
    @Autowired ProductMapper productMapper;
    @Autowired OrderMapper orderMapper;
    @Autowired OrderItemMapper orderItemMapper;
    @Autowired UserService userService;
    @Autowired StringRedisTemplate redis;

    private Long registerUser() {
        String phone = "139" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        redis.opsForValue().set("sms:code:REGISTER:" + phone, "123456", Duration.ofSeconds(300));
        User u = userService.register(new RegisterRequest(
                "rev" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                "Pass1234!", phone, "123456", true));
        return u.getId();
    }

    private Long seedDeliveredOrder(Long userId) {
        Product p = new Product();
        p.setName("RevIntTest-" + System.nanoTime());
        p.setPriceInCents(2000);
        p.setStock(5);
        p.setCategory("test");
        productMapper.insert(p);

        Order o = new Order();
        o.setUserId(userId);
        o.setCustomerName("t");
        o.setCustomerEmail("t@t.com");
        o.setShippingAddress("addr");
        o.setTotalInCents(2000);
        o.setStatus(OrderStatus.DELIVERED);
        o.setIdempotencyKey("ik-" + System.nanoTime());
        orderMapper.insert(o);

        OrderItem it = new OrderItem();
        it.setOrderId(o.getId());
        it.setProductId(p.getId());
        it.setName(p.getName());
        it.setPriceInCents(2000);
        it.setQuantity(1);
        orderItemMapper.insert(it);
        return o.getId();
    }

    @Test
    void review_completesOrder_andFeedsRatingSort() {
        Long userId = registerUser();
        Long orderId = seedDeliveredOrder(userId);

        ReviewCreateRequest req = new ReviewCreateRequest();
        req.setOrderId(orderId);
        // productId resolved from the order's item
        List<OrderItem> items = orderService.listItems(orderId, userId);
        req.setProductId(items.get(0).getProductId());
        req.setQualityStars(5); req.setLogisticsStars(5); req.setServiceStars(5);
        req.setContent("great"); req.setImageUrls(List.of()); req.setIsAnonymous(false);

        ReviewVO vo = reviewService.create(req, userId);
        assertThat(vo.getId()).isNotNull();

        // order is now COMPLETED (the only item was reviewed)
        assertThat(orderService.getById(orderId, userId).getStatus()).isEqualTo(OrderStatus.COMPLETED);

        // 未评价 tab: this order no longer appears (it's fully reviewed)
        assertThat(orderService.listUnreviewedOrders(userId))
                .extracting(Order::getId).doesNotContain(orderId);
    }

    @Test
    void unreviewedOrder_appearsInTab_thenDisappearsAfterReview() {
        Long userId = registerUser();
        Long orderId = seedDeliveredOrder(userId);
        assertThat(orderService.listUnreviewedOrders(userId))
                .extracting(Order::getId).contains(orderId);

        ReviewCreateRequest req = new ReviewCreateRequest();
        req.setOrderId(orderId);
        req.setProductId(orderService.listItems(orderId, userId).get(0).getProductId());
        req.setQualityStars(4); req.setLogisticsStars(4); req.setServiceStars(4);
        reviewService.create(req, userId);

        assertThat(orderService.listUnreviewedOrders(userId))
                .extracting(Order::getId).doesNotContain(orderId);
    }

    @Test
    void edit_afterSubmit_updatesReview() {
        Long userId = registerUser();
        Long orderId = seedDeliveredOrder(userId);
        ReviewCreateRequest req = new ReviewCreateRequest();
        req.setOrderId(orderId);
        req.setProductId(orderService.listItems(orderId, userId).get(0).getProductId());
        req.setQualityStars(3); req.setLogisticsStars(3); req.setServiceStars(3);
        ReviewVO vo = reviewService.create(req, userId);

        ReviewUpdateRequest upd = new ReviewUpdateRequest();
        upd.setQualityStars(5); upd.setLogisticsStars(5); upd.setServiceStars(5);
        upd.setContent("edited text");
        ReviewVO updated = reviewService.update(vo.getId(), upd, userId);
        assertThat(updated.getQualityStars()).isEqualTo(5);
        assertThat(updated.isEdited()).isTrue();
    }
}
