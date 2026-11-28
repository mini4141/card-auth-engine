local avail = tonumber(redis.call('GET', KEYS[1]))
if avail == nil then return -2 end
local amt = tonumber(ARGV[2])
if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 1 then return avail end
if avail >= amt then
  redis.call('DECRBY', KEYS[1], amt)
  redis.call('HSET', KEYS[2], ARGV[1], amt)
  return avail - amt
end
return -1
