-- KEYS: stock:{pid} ...   ARGV: qty, qty, ...  (paired by index)
-- Returns 'OK' if ALL items have sufficient stock (and decrements all); 'FAIL' otherwise (no mutations).
-- Two-pass all-or-nothing: pass 1 validates every key has stock >= qty; pass 2 decrby's every key.
-- A missing key → redis.call('get') returns false (nil) → tonumber(nil) is nil → treated as
-- insufficient → 'FAIL' (fail-closed: a sale whose stock was never loaded into Redis cannot be claimed).
local to_decr = {}
for i = 1, #KEYS do
  local s = tonumber(redis.call('get', KEYS[i]))
  local q = tonumber(ARGV[i])
  if not s or s < q then
    return 'FAIL'
  end
  to_decr[i] = { KEYS[i], q }
end
for _, pair in ipairs(to_decr) do
  redis.call('decrby', pair[1], pair[2])
end
return 'OK'
