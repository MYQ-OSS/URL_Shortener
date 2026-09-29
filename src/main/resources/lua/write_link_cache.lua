-- DB 回源开始时记录的版本必须仍然有效；否则状态更新可能已提交。
local current = redis.call('GET', KEYS[1]) or '0'
if current ~= ARGV[1] then return 0 end
redis.call('DEL', KEYS[3])
redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[2])
return 1
