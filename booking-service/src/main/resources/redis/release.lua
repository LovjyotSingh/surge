-- Gives a hold back without selling it (checkout lost the seats to another order).
-- Same compare-and-delete steps as finalize.lua. The seats MySQL says are already SOLD (the ones the checkout
-- lost) are added to the sold set in the same step, so Redis stops offering them. The rest are not added.
-- KEYS[1]=sold set, KEYS[2]=hold hash, KEYS[3]=userhold, KEYS[4]=holds:expiry, KEYS[5]=userholds, KEYS[6..]=seat keys
-- ARGV[1]=holdId ARGV[2]=member ARGV[3..]=seat ids that are SOLD in MySQL (may be none)
for i = 3, #ARGV do
  redis.call('SADD', KEYS[1], ARGV[i])
end
for i = 6, #KEYS do
  if redis.call('GET', KEYS[i]) == ARGV[1] then redis.call('DEL', KEYS[i]) end
end
redis.call('DEL', KEYS[2])
if redis.call('GET', KEYS[3]) == ARGV[1] then redis.call('DEL', KEYS[3]) end
redis.call('ZREM', KEYS[4], ARGV[2])
redis.call('ZREM', KEYS[5], ARGV[1])
return {'OK'}
