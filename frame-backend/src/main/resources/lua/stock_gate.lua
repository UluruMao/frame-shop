-- KEYS: stock:{pid} ...   ARGV: qty, qty, ...  (paired by index)
-- Returns 'OK' if ALL items have sufficient stock (and decrements all);
--         'NOT_INITIALIZED' if ANY key is missing (nil) — the sale was never loaded into Redis;
--         'FAIL' if a key exists but stock < qty (insufficient — sold out).
-- No mutations happen unless the result is 'OK' (two-pass all-or-nothing).
-- The missing-key vs insufficient-stock distinction matters for the API contract:
--   'NOT_INITIALIZED' → 503 "stock not initialized" (operator never opened the sale)
--   'FAIL'             → 409 "sold out" (sale is live, but the item ran out)
local to_decr = {}
for i = 1, #KEYS do
  local raw = redis.call('get', KEYS[i])
  local s = tonumber(raw)
  local q = tonumber(ARGV[i])
  if not s then
    -- Key missing (raw is false/nil) — distinct from "exists but < q".
    return 'NOT_INITIALIZED'
  end
  if s < q then
    return 'FAIL'
  end
  to_decr[i] = { KEYS[i], q }
end
for _, pair in ipairs(to_decr) do
  redis.call('decrby', pair[1], pair[2])
end
return 'OK'
