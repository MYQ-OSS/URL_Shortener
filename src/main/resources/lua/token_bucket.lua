-- 令牌桶限流。
--
-- 为什么必须写成 Lua：令牌桶要"读余量 -> 按时间差补发 -> 扣减"三步。
-- 拆成多条 Redis 命令的话，两个请求可能同时读到同一个余量各自扣减 -> 超发。
-- Redis 执行 Lua 是原子的，这才是用 Lua 的理由（不是为了少几次网络往返）。
--
-- KEYS[1]   桶的 key
-- ARGV[1]   桶容量 capacity
-- ARGV[2]   每秒补充令牌数 refillPerSec
-- ARGV[3]   当前时间戳（毫秒，由客户端传入）
-- ARGV[4]   本次请求要消耗的令牌数
--
-- 返回 1 = 放行，0 = 拒绝

local key          = KEYS[1]
local capacity     = tonumber(ARGV[1])
local refillPerSec = tonumber(ARGV[2])
local nowMs        = tonumber(ARGV[3])
local need         = tonumber(ARGV[4])

-- 时间戳由客户端传入而不是用 redis.call('TIME')：
-- 1) TIME 是随机命令，在旧版本 Redis 里会让脚本无法被复制到从库
-- 2) 传入时间戳让脚本变成纯函数，便于单测
-- 代价是依赖客户端时钟。生产上要么用 TIME（Redis 5+ 已支持 effects replication），
-- 要么保证 NTP 同步。这个取舍要能讲出来。

local bucket   = redis.call('HMGET', key, 'tokens', 'ts')
local tokens   = tonumber(bucket[1])
local lastTs   = tonumber(bucket[2])

if tokens == nil then
  tokens = capacity
  lastTs = nowMs
else
  -- 按经过的时间补发令牌，上限为 capacity
  local elapsed = math.max(0, nowMs - lastTs)
  local refill  = elapsed * refillPerSec / 1000.0
  tokens = math.min(capacity, tokens + refill)
  lastTs = nowMs
end

local allowed = 0
if tokens >= need then
  tokens = tokens - need
  allowed = 1
end

redis.call('HMSET', key, 'tokens', tokens, 'ts', lastTs)
-- TTL 必须设：否则每个 IP+短码 组合都会留下一个永久 key，内存无限增长。
-- 设为"把桶从空装满所需的时间 + 1s"，桶空置这么久之后重建等价于满桶。
local ttl = math.ceil(capacity / refillPerSec) + 1
redis.call('EXPIRE', key, ttl)

return allowed
