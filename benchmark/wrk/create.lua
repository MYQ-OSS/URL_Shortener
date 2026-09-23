-- wrk 脚本：压创建接口
--
--   wrk -t4 -c50 -d30s --latency -s wrk/create.lua http://127.0.0.1:8080
--
-- ⚠️ 创建接口的并发要开小（-c50 而不是 -c200）。理由不是它扛不住，
-- 而是短链的读写比是 1:500 —— 用跳转的并发量去压创建，测的是一个不存在的场景。
-- 真实的创建 QPS 大概在几百到几千，把它压到几万没有参考价值。
--
-- 设置 SHORTLINK_API_TOKEN 和其绑定的 SHORTLINK_API_GID（默认 default）。
-- 创建接口默认限流 10 QPS/gid，压测时通过服务端配置调整。

local counter = 0
local token = assert(os.getenv("SHORTLINK_API_TOKEN"), "缺少 SHORTLINK_API_TOKEN")
local gid = os.getenv("SHORTLINK_API_GID") or "default"
assert(gid:match("^[%w_-]+$"), "wrk 示例仅支持字母、数字、下划线或连字符 gid")
wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"
wrk.headers["Authorization"] = "Bearer " .. token

function request()
   counter = counter + 1
   -- forceNew=true 跳过幂等，保证每次都真的走完整的创建链路（发号 + 生成短码 + 落库）。
   -- 不加这个的话第二次起就命中幂等直接返回，测的是查询而不是创建。
   local body = string.format(
      '{"originUrl":"https://example.com/bench/%d-%d","gid":"%s","forceNew":true}',
      counter, os.time(), gid)
   return wrk.format(nil, "/api/link", nil, body)
end

local status = {}
function response(st, headers, body)
   status[st] = (status[st] or 0) + 1
end

function done(summary, latency, requests)
   io.write("\n--- 状态码分布 ---\n")
   for st, c in pairs(status) do io.write(string.format("  %d : %d\n", st, c)) end
   io.write(string.format("\n  创建 QPS : %.0f\n", summary.requests / (summary.duration / 1e6)))
   io.write(string.format("  P99      : %.2f ms\n", latency:percentile(99) / 1000))
   io.write("\n  对照检查：看 /actuator/metrics 里发号器的 syncLoadCount。\n")
   io.write("  它持续增长说明 step 太小、预加载赶不上消耗（见 ADR-002）。\n")
end
