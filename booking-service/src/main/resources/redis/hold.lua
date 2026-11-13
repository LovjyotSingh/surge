-- KEYS[1]=sold set, KEYS[2]=hold hash, KEYS[3]=userhold, KEYS[4]=holds:expiry, KEYS[5..]=seat keys
-- ARGV[1]=holdId ARGV[2]=userId ARGV[3]=eventId ARGV[4]=ttlMillis ARGV[5]=expiresAtMillis
-- ARGV[6]=seatIdsCsv ARGV[7..]=seatIds (same order as KEYS[5..])
local existing = redis.call('GET', KEYS[3])
if existing then return {'HOLD_EXISTS', existing} end
local taken = {}
for i = 5, #KEYS do
  local seatId = ARGV[i + 2]
  if redis.call('SISMEMBER', KEYS[1], seatId) == 1 or redis.call('EXISTS', KEYS[i]) == 1 then
    table.insert(taken, seatId)
  end
end
if #taken > 0 then table.insert(taken, 1, 'TAKEN'); return taken end
for i = 5, #KEYS do redis.call('SET', KEYS[i], ARGV[1], 'PX', ARGV[4]) end
local member = ARGV[3] .. ':' .. ARGV[1] .. ':' .. ARGV[6]
redis.call('HSET', KEYS[2], 'userId', ARGV[2], 'eventId', ARGV[3], 'seats', ARGV[6],
           'expiresAt', ARGV[5], 'member', member)
redis.call('PEXPIRE', KEYS[2], ARGV[4])
redis.call('SET', KEYS[3], ARGV[1], 'PX', ARGV[4])
redis.call('ZADD', KEYS[4], ARGV[5], member)
return {'OK'}
