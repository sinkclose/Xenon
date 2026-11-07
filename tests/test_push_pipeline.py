"""Verify the FCM push pipeline follows the official Telegram client."""

import unittest

from test_glass_blur import ROOT, method

MESSENGER = ROOT / "org/telegram/messenger"


class PushPipelineTest(unittest.TestCase):
    def test_keep_alive_mode_uses_server_and_user_settings(self):
        source = (MESSENGER / "ApplicationLoader.java").read_text()
        start_push_service = method(source, "public static void startPushService()")
        self.assertIn("keepAliveService", start_push_service)
        self.assertNotIn("pushDeliveryConfirmed", start_push_service)
        self.assertNotIn("pushRegistrationFailed", start_push_service)
        self.assertNotIn("isFcmDeliveryUnavailable", start_push_service)

    def test_push_registration_uses_official_response_check(self):
        source = (MESSENGER / "MessagesController.java").read_text()
        register_for_push = method(source, "public void registerForPush(")
        self.assertIn("response instanceof TLRPC.TL_boolTrue", register_for_push)
        self.assertNotIn("SharedConfig.pushRegistrationFailed", register_for_push)
        self.assertNotIn("ApplicationLoader.startPushService()", register_for_push)

    def test_fcm_payload_is_processed_before_config_is_saved(self):
        source = (MESSENGER / "PushListenerController.java").read_text()
        process_message = method(source, "public static void processRemoteMessage(")
        self.assertNotIn("pushDeliveryConfirmed", process_message)
        self.assertNotIn("SharedConfig.saveConfig()", process_message)
        self.assertLess(
            process_message.index("ApplicationLoader.postInitApplication()"),
            process_message.index("Utilities.stageQueue.postRunnable"),
        )

    def test_telegram_payload_has_priority_over_remote_actions(self):
        source = (MESSENGER / "GcmPushListenerService.java").read_text()
        self.assertIn('!data.containsKey("p") && data.containsKey("n")', source)
        self.assertIn('data.get("p")', source)

    def test_manifest_declares_firebase_service_and_permission(self):
        manifest = (ROOT.parent / "AndroidManifest.xml").read_text()
        self.assertIn('android:name="com.google.android.c2dm.permission.RECEIVE"', manifest)
        service_start = manifest.index(".GcmPushListenerService")
        service = manifest[service_start:manifest.index("</service>", service_start)]
        self.assertIn('android:exported="true"', service)
        self.assertIn('com.google.firebase.MESSAGING_EVENT', service)


if __name__ == "__main__":
    unittest.main()
