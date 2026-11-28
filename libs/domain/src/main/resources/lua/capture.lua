local amt = tonumber(redis.call('HGET', KEYS[1], ARGV[1]))
if amt == nil then return 0 end
redis.call('HDEL', KEYS[1], ARGV[1])
return amt
