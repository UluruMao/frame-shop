package com.iflytek.frame.common;

/**
 * Central Redis key builder. Replaces ~45 hand-built {@code "prefix:" + id} concatenations
 * across 11 files so key formats live in one place (and are grep-able). All methods
 * return the EXACT same strings the inline code produced — behavior-identical.
 */
public final class RedisKeys {
    private RedisKeys() {}

    public static String orderStatus(long id) { return "order_status:" + id; }
    public static String orderIdMap(String id) { return "order_id_map:" + id; }
    public static String seckill(long productId, long userId) { return "seckill:" + productId + ":" + userId; }
    public static String stock(long id) { return "stock:" + id; }
    public static String product(long id) { return "product:" + id; }
    public static String productsPage(String cacheKey) { return "products:page:" + cacheKey; }
    public static String captcha(String id) { return "captcha:" + id; }
    public static String rebindVerified(long userId) { return "rebind:verified:" + userId; }
    public static String loginFail(long userId) { return "login:fail:" + userId; }
    public static String smsCode(String purpose, String phone) { return "sms:code:" + purpose + ":" + phone; }
    public static String smsCooldown(String purpose, String phone) { return "sms:cooldown:" + purpose + ":" + phone; }
    public static String ordersPending() { return "orders:pending"; }
}
