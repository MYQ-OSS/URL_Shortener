-- wrk 脚本：按预生成的 Zipf 请求日志压跳转链路
--
--   wrk -t8 -c200 -d60s --latency -s wrk/redirect.lua http://127.0.0.1:8080
--
-- 两个关键设计：
--
-- 1) 请求序列来自预生成的文件而不是运行时随机
--    这样"优化前"和"优化后"打的是完全相同的请求序列，数据才可比。
--    运行时随机会让两次压测的缓存命中情况有差异，那对比就失去意义了。
--
-- 2) 每个线程从文件的不同偏移开始
--    否则 8 个线程会同时打同一个短码，人为制造出一个不存在的超级热点，
--    命中率虚高。

local codes = {}
local n = 0

-- setup 在主线程里为每个 wrk 线程各调用一次，用它派发线程序号
local thread_counter = 0
function setup(thread)
   thread_counter = thread_counter + 1
   thread:set("tid", thread_counter)
end

-- init 在每个 wrk 线程内独立执行一次
function init(args)
   local path = args[1] or "wrk/requests.txt"
   local f = io.open(path, "r")
   if not f then
      error("读不到请求日志: " .. path .. "\n先运行: python3 gen_requests.py")
   end
   for line in f:lines() do
      if #line > 0 then
         n = n + 1
         codes[n] = line
      end
   end
   f:close()

   -- 每个线程从不同偏移开始，且偏移是确定的（不用 random）——
   -- 因为"可复现"是压测报告可信的前提，同一份请求日志跑两次必须打出同一个序列。
   -- 9973 是质数，与 n 大概率互质，能让各线程起点尽量散开。
   idx = ((tid or 1) - 1) * 9973 % n

   if tid == 1 then
      io.write(string.format("已载入 %d 条请求\n", n))
   end
end

function request()
   idx = idx + 1
   if idx > n then idx = 1 end
   -- 不跟随重定向：我们要测的是"服务端产出 302 的能力"，
   -- 跟随重定向会把 example.com 的响应时间掺进来，测的就不是自己的系统了。
   return wrk.format("GET", "/" .. codes[idx])
end

-- 统计各状态码。302 之外的都是问题：
--   410 -> 短码不存在/已失效。如果大面积出现，说明 seed 数据和请求日志不匹配，
--          或链接已禁用/过期，需检查测试库主表记录
--   429 -> 被限流打到了。压测时应把 shortlink.rate-limit.redirect 容量与速率调大，
--          否则你测的是限流器的吞吐而不是跳转链路的吞吐
--   5xx -> 真问题
local status = {}
function response(st, headers, body)
   status[st] = (status[st] or 0) + 1
end

function done(summary, latency, requests)
   io.write("\n--- 状态码分布 ---\n")
   local total = 0
   for st, c in pairs(status) do total = total + c end
   for st, c in pairs(status) do
      io.write(string.format("  %d : %d (%.2f%%)\n", st, c, c / total * 100))
   end
   io.write("\n--- 关键指标（抄进 results/baseline.md）---\n")
   io.write(string.format("  QPS        : %.0f\n", summary.requests / (summary.duration / 1e6)))
   io.write(string.format("  P50        : %.2f ms\n", latency:percentile(50) / 1000))
   io.write(string.format("  P99        : %.2f ms\n", latency:percentile(99) / 1000))
   io.write(string.format("  P99.9      : %.2f ms\n", latency:percentile(99.9) / 1000))
   io.write(string.format("  errors     : connect=%d read=%d write=%d timeout=%d\n",
            summary.errors.connect, summary.errors.read,
            summary.errors.write, summary.errors.timeout))
   if status[302] == nil or status[302] / total < 0.95 then
      io.write("\n  !! 302 占比低于 95%，这次压测数据不可用。检查：\n")
      io.write("     1. seed 数据是否灌进去了\n")
      io.write("     2. 主表记录是否有效、连接的是否为预期测试库\n")
      io.write("     3. 限流是否把请求挡了（看 429 数量）\n")
   end
end
