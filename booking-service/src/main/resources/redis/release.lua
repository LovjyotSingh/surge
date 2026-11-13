-- Gives a hold back without selling it (checkout lost the seats to another order).
-- Same compare-and-delete steps as finalize.lua, but nothing is added to the sold set.
-- KEYS[1]=hold hash, KEYS[2]=userhold, KEYS[3]=holds:expiry, KEYS[4..]=seat keys
-- ARGV[1]=holdId ARGV[2]=member
for i = 4, #KEYS do
  if redis.call('GET', KEYS[i]) == ARGV[1] then redis.call('DEL', KEYS[i]) end
end
redis.call('DEL', KEYS[1])
if redis.call('GET', KEYS[2]) == ARGV[1] then redis.call('DEL', KEYS[2]) end
redis.call('ZREM', KEYS[3], ARGV[2])
return {'OK'}
