package com.iflytek.frame.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Password rule (UC001): ≥8 chars, ≥2 of {upper, lower, special}. Username: 4-25
 * alphanumeric only. These are pure static validators on UserServiceImpl — tested
 * directly via the private helper exposed through the public validation entry point.
 */
class RegisterValidationTest {

    private final UserServiceImpl service = new UserServiceImpl(null, null, null);

    @Test
    void passwordStrength_acceptsTwoCategories() {
        assertThat(UserServiceImpl.validPassword("Abcdef12")).isTrue();  // upper + lower (8 chars)
        assertThat(UserServiceImpl.validPassword("abcdef!1")).isTrue();   // lower + special
        assertThat(UserServiceImpl.validPassword("ABCDEF!1")).isTrue();   // upper + special
    }

    @Test
    void passwordStrength_rejectsTooFewCategoriesOrLength() {
        assertThat(UserServiceImpl.validPassword("abcdef1")).isFalse();  // only lower (+digit not a category)
        assertThat(UserServiceImpl.validPassword("Ab1!")).isFalse();       // < 8 chars
        assertThat(UserServiceImpl.validPassword("ABCDEFG1")).isFalse();  // only upper
    }

    @Test
    void username_acceptsAlphanumeric4to25_rejectsSpecialsAndOutOfRange() {
        assertThat(UserServiceImpl.validUsername("abcd")).isTrue();
        assertThat(UserServiceImpl.validUsername("user123")).isTrue();
        assertThat(UserServiceImpl.validUsername("abc")).isFalse();          // < 4
        assertThat(UserServiceImpl.validUsername("user_name")).isFalse();   // special
        assertThat(UserServiceImpl.validUsername("user@name")).isFalse();
    }
}
