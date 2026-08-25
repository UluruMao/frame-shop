package com.iflytek.frame.common;

import org.springframework.dao.DataIntegrityViolationException;
import java.util.function.Supplier;

/**
 * Collapses the {@code try { insert } catch (DataIntegrityViolationException) { recover }}
 * idempotency pattern. The recovery body differs per call site, so it's passed as a
 * Supplier. Behavior-identical to the inline try/catch it replaces.
 *
 * <p>Suitable for call sites whose recovery is a pure "compute a return value on duplicate"
 * (e.g. log + return null, or log + re-select + return). Not suitable when the catch body
 * must <em>rethrow the original exception</em> (e.g. "re-select; if still null, rethrow the
 * DB exception") — the {@code onDuplicate} Supplier has no access to the caught exception
 * object, so such sites stay inline.
 */
public final class IdempotentInsert {
    private IdempotentInsert() {}

    /** Run {@code insert}; if it violates a unique constraint, return {@code onDuplicate.get()}. */
    public static <T> T insertOr(Supplier<T> insert, Supplier<T> onDuplicate) {
        try {
            return insert.get();
        } catch (DataIntegrityViolationException e) {
            return onDuplicate.get();
        }
    }
}
