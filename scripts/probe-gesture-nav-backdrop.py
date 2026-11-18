#!/usr/bin/env python3
"""Read-only, offline candidate analysis for gesture-navigation backdrop research."""

import argparse
import json
from statistics import median


def _color(points):
    return tuple(round(median(p[c] for p in points)) for c in range(3))


def _distance(a, b):
    return max(abs(x - y) for x, y in zip(a, b))


def analyze(image, rect, *, scene="unknown", gesture=False):
    """Propose a visual experiment, never an automatic compatibility override.

    The caller supplies verified display coordinates and scene information.
    Screenshot colors alone cannot establish who owns a pixel or if it is safe
    to replace that pixel during a transition.
    """
    result = {"candidate_for_controlled_preview": False, "reason": "unknown-scene"}
    if scene != "ordinary":
        return result
    if not gesture:
        return {**result, "reason": "not-gesture-navigation"}
    width, height = image.size
    if len(rect) != 4 or any(type(v) is not int for v in rect):
        return {**result, "reason": "invalid-navigation-rectangle"}
    left, top, right, bottom = rect
    if (
        left != 0
        or right != width
        or bottom != height
        or top < 12
        or not 4 <= bottom - top <= height // 15
    ):
        return {**result, "reason": "not-a-bottom-gesture-strip"}

    # Require agreement across the whole bottom edge, not just one logo/pixel.
    segments = []
    for segment in range(4):
        start = width * (0.08 + segment * 0.21)
        points = [
            image.getpixel((int(start + width * 0.20 * i / 15), y))[:3]
            for i in range(16)
            for y in (top - 3, top - 7, top - 11)
        ]
        color = _color(points)
        if sum(_distance(p, color) <= 6 for p in points) / len(points) < 0.95:
            return {**result, "reason": "nonuniform-content-edge"}
        segments.append(color)
    content = _color(segments)
    if any(_distance(color, content) > 6 for color in segments):
        return {**result, "reason": "content-edge-disagrees-across-width"}

    # Avoid the gesture handle in the center and the display's rounded corners.
    nav_points = [
        image.getpixel((int(width * x), y))[:3]
        for x in (0.12, 0.20, 0.28, 0.72, 0.80, 0.88)
        for y in (top + 1, (top + bottom) // 2, bottom - 3)
    ]
    navigation = _color(nav_points)
    if any(_distance(p, navigation) > 6 for p in nav_points):
        return {**result, "reason": "nonuniform-navigation-background"}
    result.update(content_rgb=content, navigation_rgb=navigation, rect=rect)
    if _distance(content, navigation) <= 10:
        return {**result, "reason": "already-continuous"}
    # This probe deliberately covers the reported dark empty strip only.
    # A light or authored colored bar needs separate contrast/content analysis.
    if max(navigation) > 8:
        return {**result, "reason": "not-an-empty-dark-strip"}
    return {
        **result,
        "candidate_for_controlled_preview": True,
        "reason": "uniform-edge-above-dark-strip",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("screenshot")
    parser.add_argument("--rect", nargs=4, required=True, type=int,
                        metavar=("LEFT", "TOP", "RIGHT", "BOTTOM"))
    parser.add_argument("--scene", choices=("unknown", "ordinary", "ime", "video",
                                           "fullscreen", "transient", "split"),
                        default="unknown")
    parser.add_argument("--gesture-navigation", action="store_true")
    args = parser.parse_args()
    from PIL import Image

    with Image.open(args.screenshot) as image:
        if image.width * image.height > 40_000_000:
            parser.error("Screenshot exceeds the research probe's pixel budget")
        report = analyze(image.convert("RGB"), args.rect, scene=args.scene,
                         gesture=args.gesture_navigation)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
