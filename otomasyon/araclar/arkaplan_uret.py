#!/usr/bin/env python3
"""Sabit arka planı üretir: krem, suluboya lekeli, kağıt dokulu.

Bir kez çalıştırılır, sonuç `varliklar/arkaplan.jpg` olarak saklanır ve
her paylaşımda aynı arka plan kullanılır.

    python3 araclar/arkaplan_uret.py [--tohum 20] [--cikti varliklar/arkaplan.jpg]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

GENISLIK, YUKSEKLIK = 1080, 1350

# Örnek görseldeki krem/bej tonlar
ACIK = np.array([246, 240, 228], dtype=np.float32)
KOYU = np.array([234, 222, 203], dtype=np.float32)
LEKE = np.array([224, 209, 186], dtype=np.float32)


def _perlin_benzeri(rnd: np.random.Generator, en: int, boy: int, olcek: int) -> np.ndarray:
    """Küçük gürültüyü büyüterek yumuşak, organik bir alan üretir."""
    kucuk = rnd.random((max(2, boy // olcek), max(2, en // olcek))).astype(np.float32)
    img = Image.fromarray((kucuk * 255).astype(np.uint8)).resize((en, boy), Image.BICUBIC)
    return np.asarray(img, dtype=np.float32) / 255.0


def _katmanli_gurultu(rnd, en, boy, olcekler=(240, 120, 60, 24)) -> np.ndarray:
    toplam = np.zeros((boy, en), dtype=np.float32)
    agirlik = 0.0
    for i, o in enumerate(olcekler):
        a = 1.0 / (1.6 ** i)
        toplam += a * _perlin_benzeri(rnd, en, boy, o)
        agirlik += a
    alan = toplam / agirlik
    return (alan - alan.min()) / (alan.max() - alan.min() + 1e-6)


def uret(tohum: int = 20, en: int = GENISLIK, boy: int = YUKSEKLIK) -> Image.Image:
    rnd = np.random.default_rng(tohum)

    # 1) Yumuşak krem geçiş
    alan = _katmanli_gurultu(rnd, en, boy)
    taban = ACIK[None, None, :] + (KOYU - ACIK)[None, None, :] * alan[:, :, None] * 0.85

    # 2) Suluboya lekeleri (köşelerde yoğunlaşan yumuşak havuzlar)
    yy, xx = np.mgrid[0:boy, 0:en].astype(np.float32)
    leke_maske = np.zeros((boy, en), dtype=np.float32)
    for _ in range(7):
        cx = rnd.uniform(-0.15, 1.15) * en
        cy = rnd.uniform(-0.10, 1.10) * boy
        rx = rnd.uniform(0.22, 0.55) * en
        ry = rnd.uniform(0.18, 0.48) * boy
        d = ((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2
        leke_maske += np.clip(1.0 - d, 0.0, 1.0) ** 2 * rnd.uniform(0.25, 0.65)
    leke_maske *= 0.45 + 0.55 * _katmanli_gurultu(rnd, en, boy, (180, 90, 45))
    leke_maske = np.clip(leke_maske, 0.0, 1.0) * 0.42
    taban = taban * (1 - leke_maske[:, :, None]) + LEKE[None, None, :] * leke_maske[:, :, None]

    # 3) Kağıt lifi: ince gürültü + hafif yatay/dikey doku
    lif = rnd.normal(0.0, 1.0, (boy, en)).astype(np.float32)
    lif = np.asarray(
        Image.fromarray(((lif * 0.5 + 0.5) * 255).clip(0, 255).astype(np.uint8))
        .filter(ImageFilter.GaussianBlur(0.6)),
        dtype=np.float32,
    ) / 255.0
    taban += (lif[:, :, None] - 0.5) * 9.0

    # 4) Kenarlara doğru çok hafif koyulaşma (vinyet)
    mx = (xx / en - 0.5) * 2
    my = (yy / boy - 0.5) * 2
    vinyet = np.clip(1.0 - 0.10 * (mx ** 2 + my ** 2), 0.0, 1.0)
    taban *= vinyet[:, :, None]

    img = Image.fromarray(np.clip(taban, 0, 255).astype(np.uint8), "RGB")
    return img.filter(ImageFilter.GaussianBlur(0.35))


def main() -> None:
    ap = argparse.ArgumentParser(description="Sabit arka planı üretir.")
    ap.add_argument("--tohum", type=int, default=20, help="Farklı bir desen için değiştir.")
    ap.add_argument("--cikti", default="varliklar/arkaplan.jpg")
    ap.add_argument("--genislik", type=int, default=GENISLIK)
    ap.add_argument("--yukseklik", type=int, default=YUKSEKLIK)
    a = ap.parse_args()

    kok = Path(__file__).resolve().parent.parent
    yol = Path(a.cikti)
    yol = yol if yol.is_absolute() else kok / yol
    yol.parent.mkdir(parents=True, exist_ok=True)
    uret(a.tohum, a.genislik, a.yukseklik).save(yol, quality=96, subsampling=0)
    print(f"Arka plan kaydedildi: {yol}")


if __name__ == "__main__":
    main()
