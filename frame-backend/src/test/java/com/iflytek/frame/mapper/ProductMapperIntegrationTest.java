package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.dto.ProductDetail;
import com.iflytek.frame.dto.ProductListItem;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.entity.Review;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.util.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class ProductMapperIntegrationTest {

    @Autowired ProductMapper productMapper;
    @Autowired ReviewMapper reviewMapper;
    @Autowired JdbcTemplate jdbc;

    private void seedCatalog() {
        jdbc.execute("DELETE FROM order_item");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM product");
        jdbc.execute("DELETE FROM review");
        // two products
        jdbc.update("INSERT INTO product (id,name,price_in_cents,stock,category,is_flash_sale) " +
                "VALUES (1,'Red Widget',1000,5,'toys',0)");
        jdbc.update("INSERT INTO product (id,name,price_in_cents,stock,category,is_flash_sale) " +
                "VALUES (2,'Blue Gadget',2000,3,'toys',0)");
        jdbc.update("INSERT INTO product (id,name,price_in_cents,stock,category,is_flash_sale) " +
                "VALUES (3,'Green Gizmo',3000,0,'tools',0)");
        // PAID order: 4x Red Widget sold
        jdbc.update("INSERT INTO orders (id,user_id,customer_name,customer_email,shipping_address,total_in_cents,status,idempotency_key) " +
                "VALUES (10,1,'u','u@e.com','addr',4000,'PAID','ik-paid')");
        jdbc.update("INSERT INTO order_item (id,order_id,product_id,name,price_in_cents,quantity) " +
                "VALUES (100,10,1,'Red Widget',1000,4)");
        // PENDING order: 9x Red Widget — must NOT count as sold
        jdbc.update("INSERT INTO orders (id,user_id,customer_name,customer_email,shipping_address,total_in_cents,status,idempotency_key) " +
                "VALUES (11,1,'u','u@e.com','addr',9000,'PENDING','ik-pend')");
        jdbc.update("INSERT INTO order_item (id,order_id,product_id,name,price_in_cents,quantity) " +
                "VALUES (101,11,1,'Red Widget',1000,9)");
        // SHIPPED order: 3x Blue Gadget — MUST count as sold (widened IN-list)
        jdbc.update("INSERT INTO orders (id,user_id,customer_name,customer_email,shipping_address,total_in_cents,status,idempotency_key) " +
                "VALUES (12,1,'u','u@e.com','addr',6000,'SHIPPED','ik-ship')");
        jdbc.update("INSERT INTO order_item (id,order_id,product_id,name,price_in_cents,quantity) " +
                "VALUES (102,12,2,'Blue Gadget',2000,3)");
        // one review on Red Widget: (5+4+3)/3 = 4.0 avg
        jdbc.update("INSERT INTO review (id,order_id,user_id,product_id,quality_stars,logistics_stars,service_stars,content) " +
                "VALUES (1,10,1,1,5,4,3,'ok')");
    }

    private PageQuery q(String sortField, String sortOrderSql, List<String> keywords, String category) {
        PageQuery q = new PageQuery();
        q.setCurrent(1L);
        q.setSize(50L);
        q.setSortField(sortField);
        q.setSortOrderSql(sortOrderSql);
        q.setKeywords(keywords);
        q.setCategory(category);
        return q;
    }

    @Test
    void keyword_match_and_multiKeyword_and() {
        seedCatalog();
        IPage<ProductListItem> p = productMapper.pageProducts(new Page<>(1, 50),
                q(null, " DESC", List.of("red"), null));
        assertThat(p.getRecords()).hasSize(1);
        assertThat(p.getRecords().get(0).getName()).isEqualTo("Red Widget");

        // multi-keyword AND: "red widget" matches; "red gizmo" matches none
        assertThat(productMapper.pageProducts(new Page<>(1, 50),
                q(null, " DESC", List.of("red", "widget"), null)).getRecords()).hasSize(1);
        assertThat(productMapper.pageProducts(new Page<>(1, 50),
                q(null, " DESC", List.of("red", "gizmo"), null)).getRecords()).isEmpty();
    }

    @Test
    void special_char_keyword_returns_empty_no_crash() {
        seedCatalog();
        IPage<ProductListItem> p = productMapper.pageProducts(new Page<>(1, 50),
                q(null, " DESC", List.of("<script>'%;--"), null));
        assertThat(p.getRecords()).isEmpty();   // literal, no match, no injection
    }

    @Test
    void sales_sort_desc_paidAndShipped() {
        seedCatalog();
        List<ProductListItem> r = productMapper.pageProducts(new Page<>(1, 50),
                q("sales", " DESC", null, null)).getRecords();
        // Red Widget sold=4 (PAID only; PENDING's 9 units excluded); Blue Gadget sold=3 (SHIPPED counts).
        // Top seller = Red Widget (4), then Blue Gadget (3); both > 0 (IN-list widening exercised).
        assertThat(r.get(0).getName()).isEqualTo("Red Widget");
        assertThat(r.get(0).getSold()).isEqualTo(4);
        // SHIPPED now counts toward sold (widened IN-list); Blue Gadget has 3 SHIPPED units.
        ProductListItem blue = r.stream().filter(p -> "Blue Gadget".equals(p.getName())).findFirst().orElseThrow();
        assertThat(blue.getSold()).isEqualTo(3);
        // PENDING is still excluded: Red Widget sold would be 13 (4+9) if PENDING counted — it stays 4.
        assertThat(r.get(0).getSold()).isEqualTo(4);
    }

    @Test
    void rating_sort_desc_reviewedFirst() {
        seedCatalog();
        List<ProductListItem> r = productMapper.pageProducts(new Page<>(1, 50),
                q("rating", " DESC", null, null)).getRecords();
        // Red Widget avgRating=4.0; others 0.0 → sorts last in desc
        assertThat(r.get(0).getName()).isEqualTo("Red Widget");
        assertThat(r.get(0).getAvgRating()).isEqualTo(4.0);
        assertThat(r.get(1).getAvgRating()).isEqualTo(0.0);
    }

    @Test
    void ratingSort_reflectsNewReview() {
        seedCatalog();
        // seed a fresh product (auto-increment id, won't collide with seeded 1/2/3)
        Product p = new Product();
        p.setName("RatingSortTest-" + System.nanoTime());
        p.setPriceInCents(500);
        p.setStock(3);
        p.setCategory("test");
        productMapper.insert(p);

        // before any review: avgRating 0.0 (no review rows for this product → COALESCE to 0)
        double beforeAvg = productMapper.pageProducts(new Page<>(1, 50),
                q("rating", " DESC", null, null)).getRecords().stream()
                .filter(x -> x.getId().equals(p.getId()))
                .findFirst().map(ProductListItem::getAvgRating).orElse(-1.0);
        assertThat(beforeAvg).isEqualTo(0.0);

        // insert a 5/5/5 review → AVG((5+5+5)/3.0) = 5.0
        // order_id/user_id are synthetic (-1); the review table has no FK constraints,
        // and uk_review_order_product (order_id,product_id) is unique on the new product_id.
        Review r = new Review();
        r.setOrderId(-1L);
        r.setUserId(-1L);
        r.setProductId(p.getId());
        r.setQualityStars(5);
        r.setLogisticsStars(5);
        r.setServiceStars(5);
        reviewMapper.insert(r);

        // after the review: avgRating 5.0, and a rating|desc sort surfaces it first (> 4.0)
        List<ProductListItem> after = productMapper.pageProducts(new Page<>(1, 50),
                q("rating", " DESC", null, null)).getRecords();
        double afterAvg = after.stream()
                .filter(x -> x.getId().equals(p.getId()))
                .findFirst().map(ProductListItem::getAvgRating).orElse(-1.0);
        assertThat(afterAvg).isEqualTo(5.0);
        assertThat(after.get(0).getId()).isEqualTo(p.getId());
        assertThat(after.get(0).getAvgRating()).isEqualTo(5.0);
    }

    @Test
    void price_sort_asc_desc() {
        seedCatalog();
        List<ProductListItem> asc = productMapper.pageProducts(new Page<>(1, 50),
                q("price", " ASC", null, null)).getRecords();
        assertThat(asc.get(0).getName()).isEqualTo("Red Widget");     // 1000
        assertThat(asc.get(2).getName()).isEqualTo("Green Gizmo");    // 3000
        List<ProductListItem> desc = productMapper.pageProducts(new Page<>(1, 50),
                q("price", " DESC", null, null)).getRecords();
        assertThat(desc.get(0).getName()).isEqualTo("Green Gizmo");
    }

    @Test
    void category_filter() {
        seedCatalog();
        List<ProductListItem> r = productMapper.pageProducts(new Page<>(1, 50),
                q(null, " DESC", null, "tools")).getRecords();
        assertThat(r).hasSize(1);
        assertThat(r.get(0).getName()).isEqualTo("Green Gizmo");
    }

    @Test
    void findDetailById_returns_sold_and_avgRating() {
        seedCatalog();
        ProductDetail d = productMapper.findDetailById(1L);
        assertThat(d).isNotNull();
        assertThat(d.getName()).isEqualTo("Red Widget");
        assertThat(d.getSold()).isEqualTo(4);
        assertThat(d.getAvgRating()).isEqualTo(4.0);
    }

    @Test
    void findDetailById_nonexistent_returns_null() {
        seedCatalog();
        assertThat(productMapper.findDetailById(999999L)).isNull();
    }

    @Test
    void selectDistinctCategories() {
        seedCatalog();
        List<String> cats = productMapper.selectDistinctCategories();
        assertThat(cats).containsExactly("tools", "toys");
    }
}
