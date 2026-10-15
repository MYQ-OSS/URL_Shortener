#!/usr/bin/env python3
"""原子切换本项目 release；健康检查失败时回滚，不操作其他服务。"""

import argparse
import os
from pathlib import Path
import subprocess
import time
from urllib.request import urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("release")
    args = parser.parse_args()
    root = Path("/opt/url-shortener")
    release = Path(args.release).resolve(strict=True)
    if release.parent != root / "releases" or not (release / "URL_Shortener-0.0.1-SNAPSHOT.jar").is_file():
        parser.error("Expected a complete release directly under /opt/url-shortener/releases.")
    current = root / "current"
    if current.exists() and not current.is_symlink():
        parser.error("current exists but is not a symlink; refusing to replace it.")
    previous = current.resolve(strict=True) if current.is_symlink() else None
    pending = root / ".current-next"
    if pending.exists() or pending.is_symlink():
        parser.error("Stale .current-next found; inspect before retrying.")
    pending.symlink_to(release)
    os.replace(pending, current)
    try:
        subprocess.run(["systemctl", "restart", "url-shortener"], check=True)
        for _ in range(90):
            try:
                with urlopen("http://127.0.0.1:8081/actuator/health", timeout=2) as response:
                    if response.status == 200 and b'"UP"' in response.read():
                        subprocess.run(["systemctl", "enable", "url-shortener"], check=True)
                        print(f"ACTIVATION_OK: {release.name}")
                        return
            except (OSError, TimeoutError):
                pass
            time.sleep(2)
        raise RuntimeError("New release did not become healthy within 180 seconds.")
    except Exception:
        subprocess.run(["systemctl", "stop", "url-shortener"], check=False)
        if previous:
            pending.symlink_to(previous)
            os.replace(pending, current)
            subprocess.run(["systemctl", "start", "url-shortener"], check=False)
            print(f"ROLLED_BACK: {previous.name}")
        raise


if __name__ == "__main__":
    main()
