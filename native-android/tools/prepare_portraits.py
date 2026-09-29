#!/usr/bin/env python3
"""Convert the owner's black-backed exported portraits to real RGBA cutouts.

JPEG/RGB export flattened transparent artwork against black. Remove only the
exterior matte; preserve black hair, eyes, tie and suit via foreground-prior
segmentation. This script is idempotent once output alpha exists.
"""
from pathlib import Path
import sys

import cv2
import numpy as np
from PIL import Image


def fill_holes(binary: np.ndarray) -> np.ndarray:
    inv = np.uint8(1 - binary)
    flood = inv.copy()
    h, w = flood.shape
    cv2.floodFill(flood, np.zeros((h + 2, w + 2), np.uint8), (0, 0), 2)
    return np.uint8(binary | (flood == 1))


def prepare(file: Path) -> None:
    original = Image.open(file).convert("RGBA")
    if np.any(np.asarray(original.getchannel("A")) < 240):
        print("already has alpha:", file)
        return

    rgb = np.asarray(original.convert("RGB"))
    height, width = rgb.shape[:2]
    scale = min(1., 900. / max(height, width))
    if scale < 1.:
        width = max(1, round(width * scale))
        height = max(1, round(height * scale))
        rgb = cv2.resize(rgb, (width, height), interpolation=cv2.INTER_AREA)
    brightness = rgb.max(axis=2)

    evidence = np.uint8(brightness > 22)
    closed = cv2.morphologyEx(
        evidence, cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (29, 29))
    )
    closed = fill_holes(closed)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(closed, 8)
    if count < 2:
        raise ValueError("No person silhouette identified: " + str(file))
    subject = labels == 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    expanded = cv2.dilate(
        subject.astype("uint8"),
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (35, 35))
    ) > 0

    mask = np.full((height, width), cv2.GC_PR_BGD, np.uint8)
    mask[~expanded] = cv2.GC_BGD
    mask[subject & (brightness > 45)] = cv2.GC_FGD
    mask[subject & (brightness <= 45)] = cv2.GC_PR_FGD
    cv2.grabCut(
        rgb[:, :, ::-1], mask, None,
        np.zeros((1, 65), np.float64), np.zeros((1, 65), np.float64),
        6, cv2.GC_INIT_WITH_MASK
    )
    subject_alpha = np.uint8(
        (mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD)
    )
    subject_alpha = cv2.morphologyEx(
        subject_alpha, cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9))
    )
    subject_alpha = fill_holes(subject_alpha)
    subject_alpha = cv2.GaussianBlur(subject_alpha * 255, (3, 3), 0.7)
    transparent_share = float(np.mean(subject_alpha < 128))
    if not 0.10 < transparent_share < 0.90:
        raise ValueError("Unexpected matte coverage in " + str(file))

    cutout = Image.fromarray(np.dstack((rgb, subject_alpha)), "RGBA")
    cutout.save(file, "WEBP", quality=86, method=6, exact=True)
    print("real transparency:", file, width, height, f"{transparent_share:.1%}")


if __name__ == "__main__":
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
        "native-android/app/src/main/res/drawable-nodpi"
    )
    for stem in ("welcome", "home", "launcher"):
        prepare(folder / ("jlz_" + stem + "_portrait.webp"))
