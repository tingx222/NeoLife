-- KEYS: activity hash, purchased set, order stream, processing status hash
-- ARGV: userId, voucherId, orderId. Redis TIME avoids application clock skew.
if redis.call('EXISTS', KEYS[1]) == 0 then return 4 end
local now = redis.call('TIME')
local millis = tonumber(now[1])*1000 + math.floor(tonumber(now[2])/1000)
if millis < tonumber(redis.call('HGET',KEYS[1],'begin')) then return 2 end
if millis > tonumber(redis.call('HGET',KEYS[1],'end')) then return 3 end
if redis.call('SISMEMBER',KEYS[2],ARGV[1]) == 1 then return 5 end
if tonumber(redis.call('HGET',KEYS[1],'stock')) <= 0 then return 1 end
-- XADD before reservation so a wrong-type stream fails before inventory mutation.
redis.call('XADD',KEYS[3],'*','id',ARGV[3],'userId',ARGV[1],'voucherId',ARGV[2])
redis.call('HINCRBY',KEYS[1],'stock',-1)
redis.call('SADD',KEYS[2],ARGV[1])
redis.call('HSET',KEYS[4],ARGV[3],ARGV[1])
if KEYS[5] then redis.call('HSET',KEYS[5],ARGV[3],ARGV[2]) end
return 0
