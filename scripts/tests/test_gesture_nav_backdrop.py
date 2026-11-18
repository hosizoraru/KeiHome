import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "gesture_nav_probe", Path(__file__).resolve().parents[1] / "probe-gesture-nav-backdrop.py"
)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class Image:
    size = (400, 800)

    def __init__(self, content=(36, 36, 36), navigation=(0, 0, 0)):
        self.content = content
        self.navigation = navigation

    def getpixel(self, position):
        x, y = position
        if y >= 776:
            if 150 < x < 250 and 788 <= y <= 792:
                return (220, 220, 220)  # A handle is not a background candidate.
            return self.navigation
        return self.content


class GestureNavBackdropTest(unittest.TestCase):
    def analyze(self, image=None, rect=(0, 776, 400, 800), **kwargs):
        return probe.analyze(image or Image(), rect, scene="ordinary",
                             gesture=True, **kwargs)

    def test_empty_dark_strip_is_a_preview_candidate(self):
        result = self.analyze()
        self.assertTrue(result["candidate_for_controlled_preview"])
        self.assertEqual(result["content_rgb"], (36, 36, 36))
        self.assertEqual(result["navigation_rgb"], (0, 0, 0))

    def test_already_matching_bilibili_like_background_is_untouched(self):
        self.assertEqual(
            self.analyze(Image((23, 24, 26), (23, 24, 26)))["reason"],
            "already-continuous",
        )

    def test_scene_information_is_required(self):
        self.assertFalse(probe.analyze(Image(), (0, 776, 400, 800), gesture=True)
                         ["candidate_for_controlled_preview"])

    def test_unsupported_scenes_never_propose_a_preview(self):
        for scene in ("ime", "video", "fullscreen", "transient", "split"):
            with self.subTest(scene=scene):
                self.assertFalse(probe.analyze(Image(), (0, 776, 400, 800),
                                              scene=scene, gesture=True)
                                 ["candidate_for_controlled_preview"])

    def test_three_button_navigation_is_not_eligible(self):
        self.assertEqual(probe.analyze(Image(), (0, 776, 400, 800),
                                      scene="ordinary")["reason"],
                         "not-gesture-navigation")

    def test_invalid_geometry_is_not_guessed(self):
        for rect in ((0, 0, 400, 40), (376, 0, 400, 800), (0, 760, 400, 780),
                     (0, 700, 400, 800), (1, 776, 400, 800), (0, 0, 0, 0),
                     (0, 776.0, 400, 800), (0, 776, 400)):
            with self.subTest(rect=rect):
                self.assertFalse(self.analyze(rect=rect)["candidate_for_controlled_preview"])

    def test_an_authored_colored_bar_is_not_replaced(self):
        self.assertEqual(self.analyze(Image(navigation=(12, 30, 50)))["reason"],
                         "not-an-empty-dark-strip")

    def test_different_regions_are_not_collapsed_into_one_color(self):
        class SplitImage(Image):
            def getpixel(self, p):
                if p[1] < 776:
                    return (36, 36, 36) if p[0] < 200 else (72, 72, 72)
                return super().getpixel(p)
        self.assertFalse(self.analyze(SplitImage())["candidate_for_controlled_preview"])

    def test_a_busy_content_edge_is_rejected(self):
        class BusyImage(Image):
            def getpixel(self, p):
                if p[1] < 776:
                    return ((p[0] % 2) * 255,) * 3
                return super().getpixel(p)
        self.assertEqual(self.analyze(BusyImage())["reason"], "nonuniform-content-edge")

    def test_nonuniform_navigation_is_rejected(self):
        class BusyNavImage(Image):
            def getpixel(self, p):
                if p[1] >= 776 and p[0] > 200:
                    return (50, 0, 0)
                return super().getpixel(p)
        self.assertEqual(self.analyze(BusyNavImage())["reason"],
                         "nonuniform-navigation-background")


if __name__ == "__main__":
    unittest.main()
