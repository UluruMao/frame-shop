package com.iflytek.frame.service;

import java.util.List;

public interface StockGateService {
    /** Atomic Lua check-and-decrement for all items.
     *  Returns "OK" if all claimed; "FAIL" if a key exists but stock < qty (sold out);
     *  "NOT_INITIALIZED" if any stock:{pid} key is missing (sale never loaded). No partial
     *  mutations occur unless the result is "OK". */
    String tryClaimStock(List<Long> productIds, List<Integer> quantities);

    /** Sale-open: load stock:{id} from DB into Redis. */
    void loadStock(Long productId);

    /** Recovery: re-load stock:{id} from current DB stock (missing key mid-sale). */
    void recoverStock(Long productId);

    /** Re-credit Redis stock (consumer FAILED branch). */
    void recredit(Long productId, int qty);
}
