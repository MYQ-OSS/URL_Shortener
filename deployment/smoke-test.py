#!/usr/bin/env python3
"""在服务器内验证真实业务链路；仅打印检查结果，不输出 Token。"""

import json
from pathlib import Path
import re
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def main():
    config = dict(line.split("=", 1) for line in Path("/etc/url-shortener/app.env")
                  .read_text(encoding="utf-8").splitlines() if "=" in line)
    opener = build_opener(NoRedirect())
    api = {"Authorization": "Bearer " + config["SHORTLINK_API_TOKEN"]}
    admin = {"X-Admin-Token": config["SHORTLINK_ADMIN_TOKEN"]}

    def request(path, expected, headers=None, body=None, method=None):
        data = json.dumps(body).encode() if body is not None else None
        headers = {**(headers or {})}
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = Request("http://127.0.0.1:8081" + path, data=data, headers=headers, method=method)
        try:
            response = opener.open(req, timeout=15)
        except HTTPError as error:
            response = error
        with response:
            payload = response.read().decode()
            assert response.code == expected, f"{path}: HTTP {response.code}, expected {expected}"
            return payload, response.headers

    request("/actuator/health", 200)
    page, _ = request("/app/", 200)
    assert 'id="app"' in page
    request("/api/link/list?gid=default", 401)
    request("/admin/stats", 403)
    print("PASS: packaged Vue, health, anonymous access denied", flush=True)

    origin = f"https://example.com/?shortlink-deployment={int(time.time())}"
    body = {"gid": "default", "originUrl": origin, "describe": "deployment smoke test", "validType": 0}
    request("/api/link", 403, api, {**body, "gid": "another-owner"})
    short_url, _ = request("/api/link", 200, api, body)
    # API 正文为纯文本；兼容 JSON 字符串序列化但不改动认证逻辑。
    short_url = short_url.strip('"')
    code = short_url.rsplit("/", 1)[-1]
    assert re.fullmatch(r"[0-9a-zA-Z]{6}", code)
    assert short_url == config["SHORTLINK_DOMAIN"] + "/" + code
    repeated, _ = request("/api/link", 200, api, body)
    assert repeated.strip('"') == short_url
    listing, _ = request("/api/link/list?gid=default", 200, api)
    assert any(item["shortUri"] == code for item in json.loads(listing))
    print("PASS: tenant boundary, HTTPS creation, idempotency and list", flush=True)

    _, headers = request("/" + code, 302)
    assert headers["Location"] == origin
    request(f"/api/link/{code}/disable", 204, api, method="POST")
    request("/" + code, 410)
    request(f"/api/link/{code}/enable", 204, api, method="POST")
    request("/" + code, 302)
    request("/api/link", 400, api, {**body, "originUrl": "http://127.0.0.1/internal"})
    stats, _ = request("/admin/stats", 200, admin)
    assert json.loads(stats)["stats"]["bufferCapacity"] == 512
    print("PASS: redirect, disable/enable, private-network risk check and admin metrics", flush=True)

    # 等待真实 RabbitMQ -> Redis -> MySQL 定时落库，不手工伪造统计。
    for _ in range(40):
        result = subprocess.run(["mysql", "--batch", "--skip-column-names", "url_shortener"],
                                input=f"SELECT COALESCE(SUM(pv),0) FROM t_link_stats_daily WHERE short_uri='{code}' AND hour=-1;",
                                text=True, check=True, capture_output=True)
        pv = int(result.stdout.strip())
        if pv >= 2:
            request(f"/api/link/{code}/disable", 204, api, method="POST")
            print(f"PASS: real asynchronous statistics persisted (pv={pv}); test link left disabled", flush=True)
            print("SMOKE_TEST_OK", flush=True)
            return
        time.sleep(2)
    raise AssertionError("Statistics did not reach MySQL within 80 seconds.")


if __name__ == "__main__":
    main()
