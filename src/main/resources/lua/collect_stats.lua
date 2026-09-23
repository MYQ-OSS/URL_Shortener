-- 单机 Redis 的原子消费。重试使用同一个 msgId，不重复累计 PV。
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
-- 预检 key 类型，避免脚本中途 WRONGTYPE 导致部分写入。
for i = 2, 5 do
    local kind = redis.call('TYPE', KEYS[i]).ok
    local expected = i == 5 and 'set' or 'string'
    if kind ~= 'none' and kind ~= expected then
        return redis.error_reply('Invalid statistics key type')
    end
end
redis.call('PFADD', KEYS[3], ARGV[3])
redis.call('PFADD', KEYS[4], ARGV[4])
redis.call('INCR', KEYS[2])
for i = 2, 4 do redis.call('EXPIRE', KEYS[i], ARGV[1]) end
redis.call('SADD', KEYS[5], ARGV[5])
redis.call('SET', KEYS[1], '1', 'EX', ARGV[2])
return 1
