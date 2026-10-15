#!/usr/bin/env python3
"""证书签发后仅切换本项目站点，并安装仅匹配本域名的续期 reload hook。"""

import argparse
import os
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domain", required=True)
    args = parser.parse_args()
    domain = args.domain
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", domain):
        parser.error("Expected a lowercase hostname only.")
    if os.geteuid() != 0:
        parser.error("Run as root.")
    if not Path(f"/etc/letsencrypt/live/{domain}/fullchain.pem").is_file():
        parser.error("Issue this domain's certificate with certbot --webroot first.")
    site = Path("/etc/nginx/sites-available/url-shortener")
    previous = site.read_text(encoding="utf-8")
    if f"server_name {domain};" not in previous:
        parser.error("Existing URL Shortener site does not match this domain.")
    hook = Path("/etc/letsencrypt/renewal-hooks/deploy/url-shortener-reload")
    content = ("#!/bin/sh\nset -eu\ncase \" ${RENEWED_DOMAINS:-} \" in\n"
               f"  *\" {domain} \"*) /usr/sbin/nginx -t && /usr/bin/systemctl reload nginx ;;\n"
               "esac\n")
    if hook.is_symlink() or (hook.exists() and hook.read_text(encoding="utf-8") != content):
        parser.error("A different renewal hook already exists; inspect it before replacing.")
    template = Path(__file__).with_name("nginx-https.conf.template").read_text(encoding="utf-8")
    site.write_text(template.replace("@DOMAIN@", domain), encoding="utf-8")
    try:
        subprocess.run(["nginx", "-t"], check=True)
        subprocess.run(["systemctl", "reload", "nginx"], check=True)
    except subprocess.CalledProcessError:
        site.write_text(previous, encoding="utf-8")
        raise
    if not hook.exists():
        hook.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(hook, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o750)
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            output.write(content)
    print(f"HTTPS_ENABLED: https://{domain}/app/")


if __name__ == "__main__":
    main()
