"""Announce all commits since the previous release and the uploaded APKs."""

import html
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def release_commits(previous_commit):
    revision = f"{previous_commit}..HEAD" if previous_commit else "HEAD"
    return subprocess.check_output([
        "git", "log", "--topo-order", "--abbrev=7", "--format=%h: %s", revision, "--",
    ], text=True, encoding="utf-8").splitlines()


def build_messages(version, commits, assets):
    apks = sorted(
        (asset for asset in assets if asset["name"].endswith(".apk")),
        key=lambda asset: asset["name"],
    )
    if not apks:
        raise ValueError("Release has no uploaded APKs")
    lines = [f"<b>Xenon {html.escape(version)}</b>"]
    for commit in commits:
        # Escape after splitting so HTML entities cannot be broken across messages.
        lines.extend(html.escape(commit[offset:offset + 500]) for offset in range(0, len(commit), 500))
    lines.append("")
    for asset in apks:
        url = asset["browser_download_url"]
        if not url.startswith("https://"):
            raise ValueError("APK download URL must use HTTPS")
        name = asset["name"].removesuffix(".apk")
        abi = name.split("-", 3)[-1] if name.startswith("Xenon-") else name
        size = asset["size"] / (1024 * 1024)
        lines.append(
            f'<a href="{html.escape(url, quote=True)}">Скачать APK · {html.escape(abi)}</a>'
            f" ({size:.1f} МБ)"
        )
    messages = []
    message = ""
    for line in lines:
        if len(line.encode("utf-16-le")) // 2 > 4096:
            raise ValueError("Telegram message line exceeds 4096 characters")
        candidate = f"{message}\n{line}" if message else line
        if len(candidate.encode("utf-16-le")) // 2 > 4096:
            messages.append(message)
            message = line
        else:
            message = candidate
    if message:
        messages.append(message)
    return messages


def send_message(token, chat_id, message):
    payload = json.dumps({
        "chat_id": chat_id,
        "text": message,
        "parse_mode": "HTML",
        "link_preview_options": {"is_disabled": True},
    }).encode("utf-8")
    request = Request(
        f"https://api.telegram.org/bot{token}/sendMessage",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    for attempt in range(3):
        try:
            with urlopen(request, timeout=30) as response:
                result = json.load(response)
        except HTTPError as error:
            status = error.code
            try:
                result = json.load(error)
            except (ValueError, UnicodeError):
                raise RuntimeError(f"Telegram returned HTTP {status}") from None
        except (URLError, TimeoutError):
            # A lost response may still mean delivery succeeded; do not resend.
            raise RuntimeError("Telegram request failed; delivery is unknown") from None
        if result.get("ok") is True:
            return
        retry_after = result.get("parameters", {}).get("retry_after")
        if result.get("error_code") == 429 and attempt < 2 and isinstance(retry_after, int) and 0 < retry_after <= 60:
            time.sleep(retry_after)
            continue
        raise RuntimeError(f"Telegram rejected the message (error {result.get('error_code', 'unknown')})")


def main():
    token = os.environ.get("TG_TOKEN")
    chat_id = os.environ.get("TG_CHAT_ID")
    if not token or not chat_id:
        raise ValueError("TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID must be set")
    properties = dict(
        line.split("=", 1) for line in Path("gradle.properties").read_text().splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    )
    commits = release_commits(os.environ["PREVIOUS_RELEASE_COMMIT"])
    assets = json.loads(os.environ["RELEASE_ASSETS"])
    messages = build_messages(properties["APP_VERSION_NAME"], commits, assets)
    for message in messages:
        send_message(token, chat_id, message)
    print("Telegram announcement sent")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, RuntimeError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
