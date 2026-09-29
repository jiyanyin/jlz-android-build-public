"""Host-side guards for notification evidence and screenshot idempotency.

The Android Gradle build checks Kotlin compilation; these guards prevent a
regression to calling a posted notification 'human seen' or recapturing after
an ambiguous screenshot HTTP timeout.
"""
from pathlib import Path
import unittest

SRC = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "java" / "dev" / "jlz" / "presence"


class NotificationReceiptContracts(unittest.TestCase):
    def read(self, name):
        return (SRC / name).read_text(encoding="utf-8")

    def test_android_active_does_not_claim_user_view(self):
        adapter = self.read("notification/NotificationAdapter.kt")
        runtime = self.read("runtime/NativeRuntimeService.kt")
        self.assertIn('notification_active_in_system', adapter)
        self.assertIn('manager.activeNotifications.any', adapter)
        self.assertIn('heads_up_display_verified", false', runtime)
        self.assertIn('user_opened_verified", false', runtime)
        self.assertNotIn('code = "shown"', adapter)

    def test_click_and_reply_have_explicit_provenance_and_durable_outbox(self):
        receipts = self.read("notification/NotificationOpenReceipt.kt")
        main = self.read("MainActivity.kt")
        reply = self.read("notification/NotificationReplyReceiver.kt")
        self.assertIn('PendingNotificationEventStore(appContext).enqueue', receipts)
        self.assertIn('android_explicit_notification_tap', receipts)
        self.assertIn('android_inline_reply_saved', receipts)
        self.assertIn('EXTRA_FROM_NOTIFICATION', main)
        self.assertIn('recordReply(', reply)

    def test_screenshot_retry_reuses_same_event_id(self):
        queue = self.read("capture/PendingScreenshotQueue.kt")
        self.assertIn('remoteCapture(api, eventId)', queue)
        self.assertIn('acceptRemoteUpload(photo, eventId, metadata', queue)
        self.assertIn('filter { it.extension == "image" }', queue)

    def test_device_report_keeps_specific_verification_stage(self):
        client = self.read("runtime/RuntimeApiClient.kt")
        self.assertIn('val verification = structured?.optString("verification_status")', client)
        self.assertIn('.put("verification_status", verification)', client)


if __name__ == "__main__":
    unittest.main()
