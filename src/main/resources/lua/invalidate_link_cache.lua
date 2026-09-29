-- 状态已提交后，版本推进与缓存删除必须在同一条 Redis 原子脚本内。
redis.call('INCR', KEYS[1])
redis.call('EXPIRE', KEYS[1], 2678400)  -- 31 天，大于正向缓存上限
redis.call('DEL', KEYS[2], KEYS[3])
redis.call('PUBLISH', ARGV[1], ARGV[2])
return 1
