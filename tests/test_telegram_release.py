"""Check announcement formatting and Telegram failures without sending messages."""

import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "telegram_release", ROOT / ".github/scripts/telegram_release.py"
)
telegram = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(telegram)


def response(value):
    return io.BytesIO(json.dumps(value).encode())


def apk(name="Xenon-12.10.1-7038-arm64-v8a.apk"):
    return {
        "name": name,
        "size": 100 * 1024 * 1024,
        "browser_download_url": "https://github.com/example/client/releases/download/abc/" + name,
    }


class TelegramReleaseTest(unittest.TestCase):
    def test_concise_message_with_direct_download_for_large_apk(self):
        message = telegram.build_message("12.10.1", "Fix header", [apk()])
        self.assertEqual(message.splitlines()[:3], ["<b>Xenon 12.10.1</b>", "Fix header", ""])
        self.assertIn("Скачать APK · arm64-v8a", message)
        self.assertIn("/releases/download/abc/", message)
        self.assertIn("100.0 МБ", message)
        self.assertNotIn("SHA256", message)
        self.assertNotIn("actions/runs", message)

    def test_escapes_commit_and_download_links(self):
        asset = apk()
        asset["browser_download_url"] += '?x="a"&y=b'
        message = telegram.build_message("12.10.1", "Fix <header> & title", [asset])
        self.assertIn("Fix &lt;header&gt; &amp; title", message)
        self.assertIn("?x=&quot;a&quot;&amp;y=b", message)

    def test_no_apks_or_insecure_url_fails_before_send(self):
        for assets in ([], [{"name": "client.aab"}], [dict(apk(), browser_download_url="http://example.com/a.apk")]):
            with self.subTest(assets=assets), self.assertRaises(ValueError):
                telegram.build_message("1", "Fix", assets)

    def test_stable_order_and_aab_filtered(self):
        assets = [apk("Xenon-1-1-universal.apk"), {"name": "client.aab"}, apk()]
        message = telegram.build_message("1", "Fix", assets)
        self.assertLess(message.index("universal"), message.index("arm64-v8a"))
        self.assertNotIn("client.aab", message)

    def test_long_commit_is_bounded(self):
        message = telegram.build_message("1", "a" * 10000, [apk()])
        self.assertEqual(len(message.splitlines()[1]), 240)

    @patch.object(telegram, "urlopen")
    def test_success_posts_json_and_disables_preview(self, open_url):
        open_url.return_value = response({"ok": True})
        telegram.send_message("test-token", "-123", "message")
        request = open_url.call_args.args[0]
        payload = json.loads(request.data)
        self.assertEqual(payload["chat_id"], "-123")
        self.assertEqual(payload["text"], "message")
        self.assertTrue(payload["link_preview_options"]["is_disabled"])
        self.assertEqual(request.get_method(), "POST")
        open_url.assert_called_once()

    @patch.object(telegram, "urlopen")
    def test_api_failure_even_with_http_200(self, open_url):
        open_url.return_value = response({"ok": False, "error_code": 400})
        with self.assertRaisesRegex(RuntimeError, "error 400"):
            telegram.send_message("test-token", "-123", "message")

    @patch.object(telegram.time, "sleep")
    @patch.object(telegram, "urlopen")
    def test_rate_limit_retries_only_when_explicitly_rejected(self, open_url, sleep):
        limited = HTTPError("https://example.com", 429, "rate limit", {}, response({
            "ok": False, "error_code": 429, "parameters": {"retry_after": 2},
        }))
        open_url.side_effect = [limited, response({"ok": True})]
        telegram.send_message("test-token", "-123", "message")
        sleep.assert_called_once_with(2)
        self.assertEqual(open_url.call_count, 2)

    @patch.object(telegram, "urlopen")
    def test_network_failure_never_retries_or_leaks_token(self, open_url):
        open_url.side_effect = URLError("https://api.telegram.org/botSECRET/sendMessage")
        with self.assertRaises(RuntimeError) as caught:
            telegram.send_message("SECRET", "-123", "message")
        self.assertNotIn("SECRET", str(caught.exception))
        open_url.assert_called_once()

    @patch.object(telegram, "urlopen")
    def test_http_failure_without_json(self, open_url):
        open_url.side_effect = HTTPError("https://example.com", 502, "bad gateway", {}, io.BytesIO(b"bad gateway"))
        with self.assertRaisesRegex(RuntimeError, "HTTP 502"):
            telegram.send_message("test-token", "-123", "message")


if __name__ == "__main__":
    unittest.main()
