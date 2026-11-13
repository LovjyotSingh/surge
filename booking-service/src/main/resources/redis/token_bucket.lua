-- KEYS[1]=bucket hash {tokens, ts}; ARGV[1]=capacity ARGV[2]=refill per second
-- Returns {allowed(0|1), retryAfterMillis}. Time comes from Redis, never the client.
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end
local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + elapsed * rate / 1000)
local allowed = 0
local retry = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry = math.ceil((1 - tokens) / rate * 1000)
end
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000))
return {allowed, retry}
