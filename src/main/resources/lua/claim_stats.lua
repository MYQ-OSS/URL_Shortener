-- 同一 Redis 上将增量与 dirty 成员原子转移到持久的待落库批次。
local pending = redis.call('GET', KEYS[2])
if pending then return pending end
local members = redis.call('SRANDMEMBER', KEYS[1], tonumber(ARGV[2]))
if #members == 0 then return nil end
local rows = {}
local pvKeys = {}
for _, member in ipairs(members) do
    local date, hour, uri = string.match(member, '^([^|]+)|([^|]+)|([^|]+)$')
    if not date then return redis.error_reply('Invalid dirty member') end
    local key = 'sl:st:pv:' .. date .. ':' .. hour .. ':' .. uri
    local pv = tonumber(redis.call('GET', key) or '0')
    if not pv then return redis.error_reply('Invalid PV value') end
    table.insert(rows, {date=date, hour=tonumber(hour), uri=uri, pv=pv})
    table.insert(pvKeys, key)
end
local payload = cjson.encode({id=ARGV[1], rows=rows})
redis.call('SET', KEYS[2], payload)
for i, member in ipairs(members) do
    redis.call('SREM', KEYS[1], member)
    redis.call('DEL', pvKeys[i])
end
return payload
