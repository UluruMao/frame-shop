package com.iflytek.frame.mapper;

import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.entity.Review;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class ReviewMapperIntegrationTest {

    @Autowired ReviewMapper reviewMapper;
    @Autowired com.iflytek.frame.mapper.OrderMapper orderMapper;
    @Autowired com.iflytek.frame.mapper.OrderItemMapper orderItemMapper;
    @Autowired com.iflytek.frame.mapper.ProductMapper productMapper;

    /** Seed a minimal DELIVERED order + 1 line item, return the order id + product id. */
    private long[] seedDeliveredOrder() {
        Product p = new Product();
        p.setName("ReviewMapperTest-" + System.nanoTime());
        p.setPriceInCents(1000);
        p.setStock(10);
        p.setCategory("test");
        productMapper.insert(p);

        Order o = new Order();
        o.setUserId(1L);
        o.setCustomerName("t");
        o.setCustomerEmail("t@t.com");
        o.setShippingAddress("addr");
        o.setTotalInCents(1000);
        o.setStatus(com.iflytek.frame.common.OrderStatus.DELIVERED);
        o.setIdempotencyKey("ik-" + System.nanoTime());
        orderMapper.insert(o);

        OrderItem it = new OrderItem();
        it.setOrderId(o.getId());
        it.setProductId(p.getId());
        it.setName(p.getName());
        it.setPriceInCents(1000);
        it.setQuantity(1);
        orderItemMapper.insert(it);
        return new long[]{ o.getId(), p.getId() };
    }

    @Test
    void insert_andSelectByOrderAndProduct_roundTrip() {
        long[] ids = seedDeliveredOrder();
        long orderId = ids[0], productId = ids[1];

        Review r = new Review();
        r.setOrderId(orderId);
        r.setUserId(1L);
        r.setProductId(productId);
        r.setQualityStars(4);
        r.setLogisticsStars(5);
        r.setServiceStars(3);
        r.setContent("ok");
        r.setImages("[\"/uploads/reviews/a.jpg\"]");
        r.setIsAnonymous(false);
        int rows = reviewMapper.insert(r);
        assertThat(rows).isEqualTo(1);
        assertThat(r.getId()).isNotNull();

        Review found = reviewMapper.selectByOrderAndProduct(orderId, productId);
        assertThat(found).isNotNull();
        assertThat(found.getQualityStars()).isEqualTo(4);
        assertThat(found.getImages()).isEqualTo("[\"/uploads/reviews/a.jpg\"]");
    }

    @Test
    void selectByOrderId_returnsAllForOrder() {
        long[] ids = seedDeliveredOrder();
        long orderId = ids[0], productId = ids[1];
        Review r = new Review();
        r.setOrderId(orderId); r.setUserId(1L); r.setProductId(productId);
        r.setQualityStars(5); r.setLogisticsStars(5); r.setServiceStars(5);
        reviewMapper.insert(r);
        assertThat(reviewMapper.selectByOrderId(orderId)).hasSize(1);
    }
}
