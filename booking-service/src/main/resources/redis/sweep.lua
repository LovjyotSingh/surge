-- KEYS[1]=holds:expiry, KEYS[2]=sold set, KEYS[3..]=seat keys
-- ARGV[1]=member, ARGV[2..]=seatIds (same order as KEYS[3..])
-- Returns {} if another instance (or checkout) already removed the member; otherwise {'OK', <freed seatIds>...}
-- where freed = seats whose key is gone and that are not sold (not re-held, not bought).
if redis.call('ZREM', KEYS[1], ARGV[1]) == 0 then return {} end
local result = {'OK'}
for i = 3, #KEYS do
  if redis.call('EXISTS', KEYS[i]) == 0 and redis.call('SISMEMBER', KEYS[2], ARGV[i - 1]) == 0 then
    table.insert(result, ARGV[i - 1])
  end
end
return result
