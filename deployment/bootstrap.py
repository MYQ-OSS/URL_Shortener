#!/usr/bin/env python3
"""首次安装专用：只创建本项目资源，遇到已有同名资源直接停止。"""

import argparse
import grp
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess


TEMPLATES = Path(__file__).resolve().parent
CONFIG = Path("/etc/url-shortener")
UNITS = ("url-shortener", "url-shortener-redis", "url-shortener-rabbitmq")


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def mysql(sql):
    return run("mysql", "--batch", "--skip-column-names", input=sql,
               capture_output=True).stdout.strip()


def create_file(path, content, mode=0o644, group=None):
    # O_EXCL 防止覆盖已有配置或沿符号链接写入别的项目。
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
        output.write(content)
    if group:
        os.chown(path, 0, grp.getgrnam(group).gr_gid)


def template(name, **values):
    result = (TEMPLATES / name).read_text(encoding="utf-8")
    for key, value in values.items():
        result = result.replace(f"@{key}@", value)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domain", required=True)
    args = parser.parse_args()
    domain = args.domain
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", domain) or len(domain) > 55:
        parser.error("Use a lowercase hostname, without scheme or path (max 55 characters).")
    if os.geteuid() != 0:
        parser.error("Run as root on the deployment host.")
    for command in ("java", "mysql", "redis-server", "rabbitmq-server", "nginx"):
        if not shutil.which(command):
            parser.error(f"Install the prerequisite first: {command}")
    for target in (CONFIG, Path("/etc/nginx/sites-available/url-shortener"),
                   Path("/etc/nginx/sites-enabled/url-shortener"),
                   *(Path(f"/etc/systemd/system/{name}.service") for name in UNITS)):
        if target.exists() or target.is_symlink():
            parser.error(f"Already exists; refusing to overwrite: {target}")
    if mysql("SELECT COUNT(*) FROM information_schema.SCHEMATA "
             "WHERE SCHEMA_NAME='url_shortener';") != "0":
        parser.error("Database url_shortener already exists; inspect it before installing.")
    if mysql("SELECT COUNT(*) FROM mysql.user WHERE User='url_shortener';") != "0":
        parser.error("MySQL account url_shortener already exists.")
    # 所有监听口都必须空闲；不停止或替换占用它们的服务。
    for port in (8081, 6381, 5673, 25673, 43691):
        listeners = run("ss", "-H", "-ltn", "sport", "=", f":{port}",
                        capture_output=True).stdout.strip()
        if listeners:
            parser.error(f"Port is already in use: {port}")

    run("useradd", "--system", "--user-group", "--no-create-home",
        "--home-dir", "/nonexistent", "--shell", "/usr/sbin/nologin", "url-shortener")
    CONFIG.mkdir(mode=0o755)
    passwords = {key: secrets.token_hex(32) for key in ("db", "redis", "rabbit", "api", "admin")}
    env = {
        "SPRING_PROFILES_ACTIVE": "small",
        "SPRING_DATASOURCE_URL": "jdbc:mysql://127.0.0.1:3306/url_shortener?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true",
        "SPRING_DATASOURCE_USERNAME": "url_shortener",
        "SPRING_DATASOURCE_PASSWORD": passwords["db"],
        "SPRING_DATA_REDIS_PASSWORD": passwords["redis"],
        "SPRING_RABBITMQ_USERNAME": "url_shortener",
        "SPRING_RABBITMQ_PASSWORD": passwords["rabbit"],
        "SHORTLINK_DOMAIN": f"https://{domain}",
        "SHORTLINK_API_TOKEN": passwords["api"],
        "SHORTLINK_ADMIN_TOKEN": passwords["admin"],
    }
    create_file(CONFIG / "app.env", "".join(f"{key}={value}\n" for key, value in env.items()), 0o600)
    create_file(CONFIG / "access.env", "GID=default\n" + "".join(
        f"{key}={env[key]}\n" for key in ("SHORTLINK_API_TOKEN", "SHORTLINK_ADMIN_TOKEN")), 0o600)
    create_file(CONFIG / "redis.conf", template("redis.conf.template",
                REDIS_PASSWORD=passwords["redis"]), 0o640, "redis")
    create_file(CONFIG / "rabbitmq.conf", template("rabbitmq.conf.template",
                RABBITMQ_PASSWORD=passwords["rabbit"]), 0o640, "rabbitmq")
    create_file(CONFIG / "enabled_plugins", "[].\n")

    # 仅 root 执行 DDL；运行账号没有其他库权限，也没有建表/授权权限。
    mysql("CREATE DATABASE url_shortener CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;\n"
          f"CREATE USER 'url_shortener'@'127.0.0.1' IDENTIFIED BY '{passwords['db']}' "
          "WITH MAX_USER_CONNECTIONS 4;\n"
          "GRANT SELECT, INSERT, UPDATE, DELETE ON url_shortener.* "
          "TO 'url_shortener'@'127.0.0.1';\n")
    schema = (TEMPLATES.parent / "sql/schema.sql").read_text(encoding="utf-8")
    mysql("USE url_shortener;\n" + schema)

    for name in UNITS:
        create_file(Path(f"/etc/systemd/system/{name}.service"),
                    (TEMPLATES / f"{name}.service").read_text(encoding="utf-8"))
    run("systemd-analyze", "verify", *(f"/etc/systemd/system/{name}.service" for name in UNITS))
    Path("/var/www/url-shortener-acme").mkdir(mode=0o755)
    site = Path("/etc/nginx/sites-available/url-shortener")
    create_file(site, template("nginx-http.conf.template", DOMAIN=domain))
    enabled = Path("/etc/nginx/sites-enabled/url-shortener")
    enabled.symlink_to(site)
    try:
        run("nginx", "-t")
    except subprocess.CalledProcessError:
        enabled.unlink()  # 只撤回本次创建的新链接，原站点保持不变。
        raise
    run("systemctl", "daemon-reload")
    run("systemctl", "enable", "url-shortener-redis", "url-shortener-rabbitmq")
    run("systemctl", "start", "url-shortener-redis", "url-shortener-rabbitmq")
    for name in ("url-shortener-redis", "url-shortener-rabbitmq"):
        run("systemctl", "is-active", "--quiet", name)
    run("systemctl", "reload", "nginx")
    print("BOOTSTRAP_OK: isolated database, credentials, middleware and HTTP ACME site created.")
    print("Credentials: /etc/url-shortener/access.env (root-only; never commit or print in logs).")


if __name__ == "__main__":
    main()
