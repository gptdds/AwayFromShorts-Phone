#!/usr/bin/env python3
"""Publish one dist/<version> folder as a GitHub release.

Usage: publish-release.py <version> [--repo owner/name] [--tag v<version>]
Token is read from GH_TOKEN (or AFS_GH_TOKEN).
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

DIST_ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "dist")
API = "https://api.github.com"


def request(method, url, token, data=None, content_type=None, raw=False):
    headers = {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "afs-release-script",
    }
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            body = resp.read()
            return resp.status, (body if raw else json.loads(body or b"{}"))
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    version = sys.argv[1]
    repo = "gptdds/AwayFromShorts-Phone"
    tag = "v" + version
    args = sys.argv[2:]
    for i, arg in enumerate(args):
        if arg == "--repo":
            repo = args[i + 1]
        elif arg == "--tag":
            tag = args[i + 1]
    token = os.environ.get("GH_TOKEN") or os.environ.get("AFS_GH_TOKEN")
    if not token:
        raise SystemExit("GH_TOKEN missing")

    dist = os.path.join(DIST_ROOT, version)
    notes_path = os.path.join(dist, "notes.md")
    with open(notes_path, encoding="utf-8") as fh:
        body = fh.read()

    payload = json.dumps({
        "tag_name": tag,
        "name": tag,
        "body": body,
        "draft": False,
        "prerelease": False,
    }).encode("utf-8")
    status, result = request("POST", f"{API}/repos/{repo}/releases", token, payload, "application/json")
    if status >= 300:
        raise SystemExit(f"create release failed {status}: {result}")
    upload_url = result["upload_url"].split("{")[0]
    print("created", result["html_url"])

    assets = [
        (os.path.join(dist, "afs-guard.apk"), "afs-guard.apk"),
        (os.path.join(dist, "maintenance", "afs-recovery.apk"), "afs-recovery.apk"),
        (os.path.join(dist, f"AwayFromShorts-source-{version}.zip"), f"AwayFromShorts-source-{version}.zip"),
        (os.path.join(dist, f"AwayFromShorts-Windows-{version}.zip"), f"AwayFromShorts-Windows-{version}.zip"),
        (os.path.join(dist, "manifest.json"), "manifest.json"),
        (os.path.join(dist, "SHA256SUMS.txt"), "SHA256SUMS.txt"),
    ]
    for path, name in assets:
        with open(path, "rb") as fh:
            blob = fh.read()
        status, result = request(
            "POST",
            f"{upload_url}?name={urllib.parse.quote(name)}",
            token,
            blob,
            "application/octet-stream",
        )
        if status >= 300:
            raise SystemExit(f"upload {name} failed {status}: {result}")
        print("uploaded", name, result["size"])


if __name__ == "__main__":
    main()
