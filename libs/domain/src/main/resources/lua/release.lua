local amt = tonumber(redis.call('HGET', KEYS[2], ARGV[1]))
if amt == nil then return 0 end
redis.call('INCRBY', KEYS[1], amt)
redis.call('HDEL', KEYS[2], ARGV[1])
return amt
