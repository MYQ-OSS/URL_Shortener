-- 锁续租失效后，旧 worker 不能误删新 worker 认领的下一批。
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
