-- wrk 脚本：压创建接口
--
--   wrk -t4 -c50 -d30s --latency -s wrk/create.lua http://127.0.0.1:8080
--
-- 根据目标流量从低并发开始，只对隔离测试环境加压。
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
   -- forceNew=true 跳过幂等，保证每次都真的走完整的创建链路（随机短码 + 落库）。
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
   io.write("\n  对照检查：连接池、错误率和 /admin/stats 的统计队列水位。\n")
end
