import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"
JAVA = ROOT / "app/src/main/java/dev/jlz/presence"


class AvatarV1Contract(unittest.TestCase):
    def test_phrase_library_is_tagged_and_unique(self):
        data = json.loads((ASSETS / "avatar_phrases_zh.json").read_text())
        phrases = data["phrases"]
        ids = [item["id"] for item in phrases]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertGreaterEqual(len(phrases), 35)
        self.assertTrue(all(item["text"] and item["tags"] for item in phrases))

    def test_four_bundled_packs_have_required_fallbacks(self):
        manifest = json.loads((ASSETS / "avatar_packs/manifest.json").read_text())
        self.assertEqual(4, len(manifest["packs"]))
        required = {"idle.webp", "study_watch.webp", "sleep_hug.webp",
                    "react_shy.webp", "react_angry.webp", "react_surprised.webp"}
        for pack in manifest["packs"]:
            files = {p.name for p in (ASSETS / "avatar_packs" / pack["id"]).glob("*.webp")}
            self.assertTrue(required <= files, (pack["id"], required - files))

    def test_state_machine_and_smart_capture_wiring(self):
        states = (JAVA / "overlay/QAvatarStateMachine.kt").read_text()
        for name in ("IDLE", "GENTLE", "CLINGY", "TEASE", "CATCH_MONITOR",
                     "STUDY", "SLEEPY", "SLEEPING", "WOKE_UP",
                     "NIGHT_COMPANION", "HIDDEN_EDGE", "SUSPENDED"):
            self.assertIn(name, states)
        capture = (JAVA / "capture/AutomaticCaptureCoordinator.kt").read_text()
        for contract in ("MAX_PER_SESSION = 3", "MAX_PER_HOUR = 6",
                         "HASH_DISTANCE = 6", "SCROLL_BURST_THRESHOLD = 6",
                         '"com.openai.chatgpt"', '"dev.jlz.presence"'):
            self.assertIn(contract, capture)
        accessibility = (JAVA / "screen/PresenceAccessibilityService.kt").read_text()
        self.assertIn("automaticCapture.onAccessibilitySignal", accessibility)


if __name__ == "__main__":
    unittest.main()
