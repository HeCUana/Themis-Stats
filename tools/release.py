#!/usr/bin/env python3
"""把 Themis Stats 的新版本发布到 GitHub（创建 Release 并上传 jar 附件）。

用法：
    python tools/release.py status     # 仓库信息与已有 Release
    python tools/release.py inspect    # 已有 Release 的细节（名称、附件、正文）
    python tools/release.py publish    # 创建 Release 并上传 build/libs 里的 jar
    python tools/release.py verify     # 读回线上附件的大小与摘要

凭据来自本机的 Git Credential Manager（和 `git push` 用的是同一份），
脚本**从不打印** token。本机代理不一定在跑，因此默认直连；
需要时把 RELEASE_PROXY 改成 'http://127.0.0.1:7897' 之类。

发布流程：
    1. 确认 build/libs/themis-stats-<版本>.jar 是**实测过的那一份**
       （和 server/mods/ 里的逐字节一致）；
    2. 把 docs/RELEASE-<版本>.md 的内容作为 Release 正文；
    3. 上传附件，文件名 themis-stats-<MC 版本>-<tag>.jar；
    4. 跑一次 verify，核对线上附件的 sha256 与本地构建一致。
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = "HeCUana/Themis-Stats"
TAG = "V20261021.0.1.0"
MC_VERSION = "1.21.11"
JAR = os.path.join(ROOT, "build", "libs", "themis-stats-0.1.0.jar")
NOTES = os.path.join(ROOT, "docs", "RELEASE-0.1.0.md")
# 本机代理不一定在跑，留空即直连。
RELEASE_PROXY = ""

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

OPENER = urllib.request.build_opener(
    urllib.request.ProxyHandler({"https": RELEASE_PROXY} if RELEASE_PROXY else {}))


def credential() -> str:
    """The GitHub token that the credential manager has for github.com."""
    proc = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True, timeout=60,
    )
    fields = {}
    for line in proc.stdout.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            fields[key] = value
    token = fields.get("password")
    if not token:
        raise SystemExit("拿不到 GitHub 凭据（git credential fill 没有返回 password）")
    return token


def api(token: str, path: str, method: str = "GET", payload: bytes | None = None,
        content_type: str | None = None):
    """Calls the GitHub REST API and returns (status, body)."""
    url = path if path.startswith("http") else "https://api.github.com" + path
    headers = {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "themis-stats-release",
    }
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        with OPENER.open(request, timeout=600) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


def local_digest(path: str) -> tuple[str, str]:
    with open(path, "rb") as handle:
        blob = handle.read()
    return hashlib.sha256(blob).hexdigest(), hashlib.sha1(blob).hexdigest()


def main() -> int:
    token = credential()
    action = sys.argv[1] if len(sys.argv) > 1 else "status"
    tag = sys.argv[2] if len(sys.argv) > 2 else TAG

    status, body = api(token, "/repos/" + REPO)
    if status != 200:
        print("仓库访问失败：%s %s" % (status, body[:400].decode("utf-8", "replace")))
        return 1
    info = json.loads(body)
    print("仓库：%s（默认分支 %s，私有 %s）" % (info["full_name"], info["default_branch"], info["private"]))

    status, body = api(token, "/repos/" + REPO + "/releases")
    releases = json.loads(body) if status == 200 else []
    print("已有 Release：%s" % ([r["tag_name"] for r in releases] or "无"))

    if action == "inspect":
        for release in releases:
            print("---- %s ----" % release["tag_name"])
            print("名称：%s" % release["name"])
            print("发布于：%s" % release["published_at"])
            print("地址：%s" % release["html_url"])
            print("附件：%s" % [(a["name"], a["size"]) for a in release["assets"]])
            body_text = release.get("body") or ""
            print("正文长度：%d 字符，开头：%s" % (len(body_text), body_text[:200].replace("\n", " / ")))
        return 0

    if action == "status":
        return 0

    if action == "verify":
        status, body = api(token, "/repos/" + REPO + "/releases/tags/" + tag)
        if status != 200:
            print("读取 Release 失败：%s %s" % (status, body[:300].decode("utf-8", "replace")))
            return 1
        release = json.loads(body)
        print("Release：%s（draft=%s prerelease=%s）"
              % (release["html_url"], release["draft"], release["prerelease"]))
        for asset in release["assets"]:
            print("附件 %s" % asset["name"])
            print("  大小   %s 字节（本地构建 %s 字节）"
                  % (asset["size"], os.path.getsize(JAR)))
            print("  摘要   %s" % asset.get("digest"))
            print("  下载   %s" % asset["browser_download_url"])
        print("本地 sha256 %s" % local_digest(JAR)[0])
        return 0

    if action != "publish":
        print("未知动作：%s（可用：status / inspect / publish / verify）" % action)
        return 2

    if not os.path.isfile(JAR):
        print("找不到 %s" % JAR)
        return 1
    if not os.path.isfile(NOTES):
        print("找不到 %s" % NOTES)
        return 1

    with open(NOTES, "r", encoding="utf-8") as handle:
        body_text = handle.read()

    payload = json.dumps({
        "tag_name": tag,
        "name": "Themis Stats %s" % tag,
        "body": body_text,
        "draft": False,
        "prerelease": False,
    }).encode("utf-8")
    status, body = api(token, "/repos/" + REPO + "/releases", "POST", payload, "application/json")
    if status not in (200, 201):
        print("创建 Release 失败：%s %s" % (status, body[:600].decode("utf-8", "replace")))
        return 1
    release = json.loads(body)
    print("Release 已创建：%s" % release["html_url"])

    asset_name = "themis-stats-%s-%s.jar" % (MC_VERSION, tag)
    with open(JAR, "rb") as handle:
        blob = handle.read()
    upload_url = release["upload_url"].split("{")[0]
    query = "?name=%s&label=%s" % (asset_name, "Themis%20Stats")
    status, body = api(token, upload_url + query, "POST", blob, "application/java-archive")
    if status not in (200, 201):
        print("上传附件失败：%s %s" % (status, body[:600].decode("utf-8", "replace")))
        return 1
    asset = json.loads(body)
    print("附件已上传：%s（%s 字节，摘要 %s）"
          % (asset["browser_download_url"], asset["size"], asset.get("digest")))

    status, body = api(token, "/repos/" + REPO + "/git/ref/tags/" + tag)
    if status == 200:
        print("tag %s -> %s" % (tag, json.loads(body)["object"]["sha"]))
    print("发布地址：%s" % release["html_url"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
