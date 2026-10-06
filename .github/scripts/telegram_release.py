"""Send one concise announcement using assets from the completed release."""

import html
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def build_message(version, commit, assets):
    apks = sorted(
        (asset for asset in assets if asset["name"].endswith(".apk")),
        key=lambda asset: asset["name"],
    )
    if not apks:
        raise ValueError("Release has no uploaded APKs")
    lines = [f"<b>Xenon {html.escape(version)}</b>", html.escape(commit[:240]), ""]
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
    message = "\n".join(lines)
    if len(message) > 4096:
        raise ValueError("Telegram message exceeds 4096 characters")
    return message


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
    commit = subprocess.check_output(["git", "log", "-1", "--format=%s"], text=True).strip()
    assets = json.loads(os.environ["RELEASE_ASSETS"])
    message = build_message(properties["APP_VERSION_NAME"], commit, assets)
    send_message(token, chat_id, message)
    print("Telegram announcement sent")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, RuntimeError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
