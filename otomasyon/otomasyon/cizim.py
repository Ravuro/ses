"""Sözü anlatan çizimler.

Her motif 0..1 aralığında normalize edilmiş koordinatlarla tanımlıdır ve
Pillow ile, kağıt üstüne mürekkeple çizilmiş hissi verecek şekilde çizilir.

Kendi görselini kullanmak istersen: `varliklar/cizimler/<anahtar>.png`
(şeffaf zeminli PNG) koyman yeterli — o dosya varsa vektörel çizim yerine
o kullanılır.
"""

from __future__ import annotations

import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from .ayarlar import KOK

OZEL_CIZIM_KLASORU = KOK / "varliklar" / "cizimler"
ORNEKLEME = 4  # süper örnekleme katsayısı (kenar yumuşatma için)


# --------------------------------------------------------------------------
# Geometri yardımcıları
# --------------------------------------------------------------------------

def _daire_noktalari(mx, my, rx, ry, bas=0.0, bit=360.0, adet=64):
    return [
        (mx + rx * math.cos(math.radians(a)), my + ry * math.sin(math.radians(a)))
        for a in [bas + (bit - bas) * i / adet for i in range(adet + 1)]
    ]


def _yildiz_noktalari(mx, my, dis, ic, uc=5, donus=-90.0):
    noktalar = []
    for i in range(uc * 2):
        r = dis if i % 2 == 0 else ic
        a = math.radians(donus + i * 180.0 / uc)
        noktalar.append((mx + r * math.cos(a), my + r * math.sin(a)))
    noktalar.append(noktalar[0])
    return noktalar


def _kalp_noktalari(mx, my, olcek, adet=90):
    noktalar = []
    for i in range(adet + 1):
        t = 2 * math.pi * i / adet
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        noktalar.append((mx + x * olcek / 16.0, my - y * olcek / 16.0))
    return noktalar


def _dalga(x1, x2, y, genlik, dalga_boyu, adet=48):
    return [
        (x1 + (x2 - x1) * i / adet,
         y + genlik * math.sin(2 * math.pi * (x1 + (x2 - x1) * i / adet) / dalga_boyu))
        for i in range(adet + 1)
    ]


def _bulut_hatti(daireler, taban_y, adet=140):
    """Üst üste binen dairelerin birleşiminden temiz bir bulut dış hattı çıkarır."""
    x1 = min(cx - r for cx, _, r, _ in daireler)
    x2 = max(cx + r for cx, _, r, _ in daireler)
    hat = []
    for i in range(adet + 1):
        x = x1 + (x2 - x1) * i / adet
        ust = [cy - ry * math.sqrt(max(0.0, 1 - ((x - cx) / r) ** 2))
               for cx, cy, r, ry in daireler if abs(x - cx) < r]
        if ust:
            hat.append((x, min(ust)))
    return [(x1, taban_y)] + hat + [(x2, taban_y), (x1, taban_y)]


def C(noktalar, k=1.0):
    """Açık çizgi."""
    return {"t": "c", "n": list(noktalar), "k": k}


def K(noktalar, k=1.0):
    """Kapalı çizgi."""
    n = list(noktalar)
    if n[0] != n[-1]:
        n.append(n[0])
    return {"t": "c", "n": n, "k": k}


def D(noktalar):
    """Dolu alan."""
    return {"t": "dolu", "n": list(noktalar)}


# --------------------------------------------------------------------------
# Motifler
# --------------------------------------------------------------------------

def _yuruyen():
    return [
        K(_daire_noktalari(0.523, 0.150, 0.058, 0.066), 1.0),                # baş
        C([(0.478, 0.132), (0.462, 0.168), (0.472, 0.198)], 0.7),            # saç
        C([(0.496, 0.212), (0.502, 0.246)], 0.7),                            # boyun
        K([(0.462, 0.250), (0.436, 0.378), (0.428, 0.512), (0.548, 0.512),
           (0.562, 0.375), (0.552, 0.246), (0.512, 0.230)], 1.15),           # palto
        C([(0.512, 0.244), (0.504, 0.502)], 0.45),                           # ön kapanış
        C([(0.428, 0.512), (0.398, 0.470), (0.432, 0.428)], 0.55),           # savrulan etek
        C([(0.470, 0.276), (0.414, 0.376), (0.380, 0.446)], 0.95),           # arka kol
        C([(0.550, 0.270), (0.608, 0.360), (0.632, 0.430)], 0.95),           # ön kol
        C([(0.478, 0.512), (0.412, 0.652), (0.346, 0.810)], 1.1),            # arka bacak
        C([(0.524, 0.512), (0.602, 0.634), (0.646, 0.800)], 1.1),            # ön bacak
        C([(0.346, 0.810), (0.292, 0.852), (0.286, 0.874)], 0.95),           # arka ayak
        C([(0.646, 0.800), (0.706, 0.842), (0.712, 0.868)], 0.95),           # ön ayak
        C([(0.452, 0.400), (0.512, 0.414)], 0.4),                            # kumaş kıvrımı
        C([(0.518, 0.428), (0.556, 0.418)], 0.4),
        C([(0.238, 0.906), (0.762, 0.906)], 0.65),                           # zemin
    ]


def _agac():
    govde = [(0.50, 0.90), (0.497, 0.78), (0.508, 0.66), (0.50, 0.56), (0.505, 0.47)]
    dallar = [
        C([(0.503, 0.62), (0.42, 0.545), (0.352, 0.50)], 0.75),
        C([(0.503, 0.58), (0.585, 0.505), (0.652, 0.465)], 0.75),
        C([(0.503, 0.52), (0.445, 0.455), (0.408, 0.415)], 0.6),
        C([(0.503, 0.50), (0.562, 0.44), (0.598, 0.40)], 0.6),
    ]
    tepe = []
    rnd = random.Random(7)
    for a in range(0, 361, 12):
        r = 0.235 + rnd.uniform(-0.028, 0.028)
        tepe.append((0.50 + r * 1.08 * math.cos(math.radians(a)),
                     0.325 + r * 0.82 * math.sin(math.radians(a))))
    tepe.append(tepe[0])
    return [
        C(govde, 1.3),
        *dallar,
        C(tepe, 1.0),
        C([(0.50, 0.895), (0.40, 0.925)], 0.7),
        C([(0.50, 0.895), (0.61, 0.928)], 0.7),
        C([(0.26, 0.935), (0.74, 0.935)], 0.65),
    ]


def _kus():
    def kanat(mx, my, o):
        return [
            C(_daire_noktalari(mx - o, my, o, o * 0.72, 195, 350), 1.0),
            C(_daire_noktalari(mx + o, my, o, o * 0.72, 190, 345), 1.0),
        ]
    return [
        *kanat(0.50, 0.42, 0.20),
        *kanat(0.24, 0.25, 0.095),
        *kanat(0.76, 0.62, 0.075),
        *kanat(0.30, 0.72, 0.055),
    ]


def _kapi():
    return [
        K([(0.24, 0.90), (0.24, 0.20), (0.76, 0.20), (0.76, 0.90)], 1.25),
        K([(0.30, 0.865), (0.30, 0.255), (0.70, 0.255), (0.70, 0.865)], 0.8),
        # açılan kanat (perspektif)
        K([(0.30, 0.865), (0.30, 0.255), (0.11, 0.325), (0.11, 0.815)], 1.05),
        K(_daire_noktalari(0.335, 0.575, 0.016, 0.016), 0.9),
        # eşikten sızan ışık
        C([(0.36, 0.86), (0.55, 0.62)], 0.5),
        C([(0.46, 0.87), (0.63, 0.66)], 0.5),
        C([(0.56, 0.875), (0.69, 0.71)], 0.5),
        C([(0.06, 0.90), (0.94, 0.90)], 0.7),
    ]


def _ay():
    dis = _daire_noktalari(0.50, 0.45, 0.27, 0.27, 60, 300)
    ic = _daire_noktalari(0.63, 0.45, 0.234, 0.234, 270, 90)
    return [
        K(dis + ic, 1.15),
        K(_yildiz_noktalari(0.235, 0.235, 0.052, 0.020), 0.85),
        K(_yildiz_noktalari(0.815, 0.685, 0.040, 0.015), 0.85),
        K(_yildiz_noktalari(0.285, 0.755, 0.030, 0.012), 0.8),
    ]


def _gemi():
    return [
        C([(0.235, 0.665), (0.30, 0.755), (0.70, 0.755), (0.765, 0.665)], 1.2),  # tekne
        C([(0.235, 0.665), (0.765, 0.665)], 0.9),
        C([(0.50, 0.655), (0.50, 0.195)], 1.1),                                   # direk
        K([(0.512, 0.225), (0.512, 0.60), (0.735, 0.60)], 1.0),                   # büyük yelken
        K([(0.488, 0.285), (0.488, 0.60), (0.315, 0.60)], 0.95),                  # küçük yelken
        C(_dalga(0.10, 0.90, 0.815, 0.016, 0.30), 0.75),
        C(_dalga(0.08, 0.92, 0.875, 0.013, 0.26), 0.6),
        C(_dalga(0.14, 0.86, 0.925, 0.010, 0.22), 0.5),
    ]


def _saat():
    return [
        K(_daire_noktalari(0.50, 0.555, 0.285, 0.285), 1.3),
        K(_daire_noktalari(0.50, 0.555, 0.245, 0.245), 0.55),
        C([(0.50, 0.555), (0.50, 0.375)], 1.0),                 # akrep
        C([(0.50, 0.555), (0.632, 0.628)], 0.9),                # yelkovan
        K(_daire_noktalari(0.50, 0.555, 0.016, 0.016), 0.9),
        *[C([(0.50 + 0.245 * math.cos(math.radians(a)), 0.555 + 0.245 * math.sin(math.radians(a))),
             (0.50 + 0.212 * math.cos(math.radians(a)), 0.555 + 0.212 * math.sin(math.radians(a)))], 0.7)
          for a in range(0, 360, 30)],
        K([(0.462, 0.272), (0.538, 0.272), (0.538, 0.235), (0.462, 0.235)], 0.9),  # tepe
        K(_daire_noktalari(0.50, 0.205, 0.042, 0.042), 1.0),                        # halka
        C([(0.458, 0.185), (0.352, 0.118), (0.258, 0.138)], 0.65),                  # zincir
    ]


def _anahtar():
    return [
        K(_daire_noktalari(0.30, 0.38, 0.145, 0.145), 1.25),
        K(_daire_noktalari(0.30, 0.38, 0.070, 0.070), 0.9),
        C([(0.405, 0.485), (0.745, 0.805)], 1.25),
        C([(0.652, 0.718), (0.592, 0.782)], 1.0),
        C([(0.712, 0.775), (0.652, 0.838)], 1.0),
        C([(0.745, 0.805), (0.706, 0.845)], 0.9),
    ]


def _dag():
    return [
        C([(0.06, 0.815), (0.34, 0.315), (0.475, 0.545), (0.575, 0.425),
           (0.78, 0.735), (0.94, 0.815)], 1.25),
        C([(0.255, 0.465), (0.305, 0.418), (0.34, 0.315), (0.385, 0.425), (0.435, 0.468)], 0.7),
        C([(0.532, 0.492), (0.575, 0.425), (0.628, 0.512)], 0.7),
        K(_daire_noktalari(0.735, 0.255, 0.088, 0.088), 0.95),
        C([(0.06, 0.815), (0.94, 0.815)], 0.85),
        C([(0.20, 0.875), (0.58, 0.875)], 0.55),
        C([(0.44, 0.925), (0.82, 0.925)], 0.5),
    ]


def _mum():
    return [
        K([(0.405, 0.865), (0.405, 0.435), (0.595, 0.435), (0.595, 0.865)], 1.2),
        C([(0.405, 0.465), (0.595, 0.465)], 0.55),
        C([(0.50, 0.435), (0.50, 0.385)], 0.9),                                      # fitil
        K([(0.50, 0.175), (0.575, 0.285), (0.552, 0.362), (0.50, 0.388),
           (0.448, 0.362), (0.425, 0.285)], 1.1),                                    # alev
        C([(0.50, 0.235), (0.532, 0.305), (0.50, 0.355), (0.472, 0.305)], 0.6),
        C([(0.418, 0.505), (0.40, 0.585), (0.415, 0.63)], 0.6),                      # akan mum
        C([(0.30, 0.865), (0.70, 0.865)], 0.9),
        *[C([(0.50 + r * math.cos(math.radians(a)), 0.275 + r * 0.9 * math.sin(math.radians(a))),
             (0.50 + (r + 0.045) * math.cos(math.radians(a)), 0.275 + (r + 0.045) * 0.9 * math.sin(math.radians(a)))], 0.45)
          for a, r in ((200, 0.185), (230, 0.175), (310, 0.175), (340, 0.185))],
    ]


def _kitap():
    return [
        C([(0.50, 0.335), (0.50, 0.775)], 1.0),
        C([(0.50, 0.335), (0.285, 0.285), (0.115, 0.325), (0.115, 0.735),
           (0.285, 0.695), (0.50, 0.775)], 1.25),
        C([(0.50, 0.335), (0.715, 0.285), (0.885, 0.325), (0.885, 0.735),
           (0.715, 0.695), (0.50, 0.775)], 1.25),
        *[C([(0.175, 0.395 + i * 0.072), (0.435, 0.435 + i * 0.072)], 0.5) for i in range(4)],
        *[C([(0.565, 0.435 + i * 0.072), (0.825, 0.395 + i * 0.072)], 0.5) for i in range(4)],
    ]


def _yagmur():
    bulut = _bulut_hatti(
        [(0.345, 0.405, 0.125, 0.115), (0.50, 0.345, 0.155, 0.145), (0.655, 0.405, 0.115, 0.105)],
        taban_y=0.455,
    )
    damlalar = []
    for x, y, u in ((0.30, 0.545, 0.10), (0.415, 0.60, 0.125), (0.53, 0.535, 0.095),
                    (0.635, 0.60, 0.12), (0.735, 0.545, 0.09), (0.365, 0.735, 0.08),
                    (0.585, 0.745, 0.085)):
        damlalar.append(C([(x, y), (x - 0.022, y + u)], 0.8))
    return [C(bulut, 1.2), *damlalar]


def _pencere():
    return [
        K([(0.20, 0.855), (0.20, 0.165), (0.80, 0.165), (0.80, 0.855)], 1.3),
        K([(0.255, 0.815), (0.255, 0.215), (0.745, 0.215), (0.745, 0.815)], 0.85),
        C([(0.50, 0.215), (0.50, 0.815)], 0.85),
        C([(0.255, 0.515), (0.745, 0.515)], 0.85),
        C([(0.155, 0.885), (0.845, 0.885)], 1.0),
        K(_daire_noktalari(0.645, 0.335, 0.055, 0.055), 0.7),          # camdan görünen ay
        C([(0.315, 0.62), (0.345, 0.70), (0.315, 0.775)], 0.5),        # perde
        C([(0.385, 0.60), (0.405, 0.70), (0.378, 0.79)], 0.45),
    ]


def _kopru():
    return [
        C([(0.06, 0.545), (0.94, 0.545)], 1.2),
        C(_daire_noktalari(0.50, 0.545, 0.285, 0.215, 180, 360), 1.2),
        *[C([(x, 0.545), (x, 0.545 - 0.215 * math.sqrt(max(0.0, 1 - ((x - 0.5) / 0.285) ** 2)))], 0.55)
          for x in (0.325, 0.40, 0.50, 0.60, 0.675)],
        C([(0.06, 0.545), (0.06, 0.50)], 0.9),
        C([(0.94, 0.545), (0.94, 0.50)], 0.9),
        C([(0.215, 0.545), (0.215, 0.775)], 0.85),
        C([(0.785, 0.545), (0.785, 0.775)], 0.85),
        C(_dalga(0.05, 0.95, 0.795, 0.014, 0.28), 0.7),
        C(_dalga(0.09, 0.91, 0.855, 0.011, 0.24), 0.55),
        C(_dalga(0.16, 0.84, 0.905, 0.009, 0.21), 0.45),
    ]


def _kalp():
    return [
        C(_kalp_noktalari(0.50, 0.50, 0.335), 1.3),
        C([(0.495, 0.245), (0.535, 0.355), (0.462, 0.455), (0.528, 0.565), (0.482, 0.70)], 0.85),
    ]


def _yildiz():
    return [
        K(_yildiz_noktalari(0.615, 0.345, 0.175, 0.070), 1.2),
        C([(0.475, 0.455), (0.245, 0.685)], 0.8),
        C([(0.525, 0.502), (0.315, 0.745)], 0.6),
        C([(0.432, 0.408), (0.205, 0.615)], 0.55),
        K(_yildiz_noktalari(0.205, 0.215, 0.038, 0.015), 0.7),
        K(_yildiz_noktalari(0.842, 0.735, 0.030, 0.012), 0.7),
    ]


def _filiz():
    return [
        C([(0.50, 0.885), (0.50, 0.66), (0.512, 0.52), (0.50, 0.435)], 1.15),
        C([(0.505, 0.585), (0.375, 0.512), (0.272, 0.565), (0.352, 0.652), (0.505, 0.585)], 1.0),
        C([(0.505, 0.51), (0.638, 0.418), (0.742, 0.462), (0.655, 0.568), (0.505, 0.51)], 1.0),
        C([(0.505, 0.585), (0.372, 0.592)], 0.5),
        C([(0.505, 0.51), (0.655, 0.478)], 0.5),
        C([(0.22, 0.885), (0.78, 0.885)], 0.9),
        C([(0.31, 0.925), (0.53, 0.925)], 0.5),
    ]


def _fener():
    return [
        K(_daire_noktalari(0.50, 0.085, 0.038, 0.038), 0.9),
        C([(0.50, 0.123), (0.50, 0.225)], 0.8),
        K([(0.365, 0.225), (0.635, 0.225), (0.585, 0.285), (0.415, 0.285)], 1.1),
        K([(0.415, 0.285), (0.585, 0.285), (0.618, 0.735), (0.382, 0.735)], 1.2),
        K([(0.345, 0.735), (0.655, 0.735), (0.655, 0.80), (0.345, 0.80)], 1.1),
        K([(0.50, 0.435), (0.578, 0.548), (0.552, 0.638), (0.50, 0.672),
           (0.448, 0.638), (0.422, 0.548)], 0.95),
        C([(0.50, 0.652), (0.50, 0.70)], 0.7),
        C([(0.435, 0.30), (0.452, 0.725)], 0.45),
        C([(0.565, 0.30), (0.548, 0.725)], 0.45),
    ]


def _yol():
    return [
        C([(0.14, 0.92), (0.35, 0.60), (0.455, 0.36)], 1.2),
        C([(0.86, 0.92), (0.65, 0.60), (0.545, 0.36)], 1.2),
        *[C([(0.50 - 0.012 * (1 - i / 5), 0.42 + i * 0.095),
             (0.50 - 0.012 * (1 - i / 5), 0.46 + i * 0.095)], 0.7) for i in range(6)],
        C([(0.10, 0.36), (0.90, 0.36)], 0.6),
        K(_daire_noktalari(0.735, 0.255, 0.078, 0.078), 0.85),
    ]


def _kum_saati():
    return [
        C([(0.285, 0.145), (0.715, 0.145)], 1.2),
        C([(0.285, 0.855), (0.715, 0.855)], 1.2),
        C([(0.335, 0.145), (0.50, 0.50), (0.335, 0.855)], 1.2),
        C([(0.665, 0.145), (0.50, 0.50), (0.665, 0.855)], 1.2),
        D([(0.382, 0.255), (0.618, 0.255), (0.50, 0.482)]),
        D([(0.50, 0.585), (0.572, 0.80), (0.428, 0.80)]),
        C([(0.50, 0.50), (0.50, 0.72)], 0.5),
    ]


def _ayna():
    return [
        K(_daire_noktalari(0.50, 0.40, 0.255, 0.285), 1.3),
        K(_daire_noktalari(0.50, 0.40, 0.205, 0.235), 0.6),
        C([(0.50, 0.685), (0.50, 0.885)], 1.2),
        C([(0.395, 0.885), (0.605, 0.885)], 1.0),
        C([(0.385, 0.315), (0.445, 0.245)], 0.5),
        C([(0.365, 0.415), (0.478, 0.285)], 0.5),
    ]


def _el():
    return [
        C([(0.31, 0.885), (0.345, 0.70), (0.40, 0.585)], 1.1),
        C([(0.40, 0.585), (0.355, 0.415), (0.392, 0.385), (0.448, 0.50)], 1.05),
        C([(0.448, 0.50), (0.432, 0.285), (0.478, 0.262), (0.512, 0.44)], 1.05),
        C([(0.512, 0.44), (0.522, 0.245), (0.572, 0.245), (0.585, 0.455)], 1.05),
        C([(0.585, 0.455), (0.618, 0.318), (0.662, 0.335), (0.648, 0.515)], 1.05),
        C([(0.648, 0.515), (0.688, 0.632), (0.652, 0.755), (0.565, 0.885)], 1.1),
        C([(0.31, 0.885), (0.565, 0.885)], 0.7),
    ]


MOTIFLER: dict[str, dict] = {
    "yuruyen":   {"ad": "yürüyen kişi",    "temalar": ["gitmek", "vazgeçiş", "yolculuk", "ayrılık", "devam etmek", "yalnızlık"], "sekiller": _yuruyen()},
    "agac":      {"ad": "yalnız ağaç",     "temalar": ["sabır", "kök", "büyümek", "yalnızlık", "dayanmak", "olgunlaşmak"],       "sekiller": _agac()},
    "kus":       {"ad": "uçan kuş",        "temalar": ["özgürlük", "gitmek", "umut", "hafiflemek", "kaçış"],                     "sekiller": _kus()},
    "kapi":      {"ad": "aralık kapı",     "temalar": ["yeni başlangıç", "veda", "fırsat", "eşik", "karar"],                     "sekiller": _kapi()},
    "ay":        {"ad": "hilal ve yıldız", "temalar": ["gece", "özlem", "hayal", "uykusuzluk", "sessizlik"],                     "sekiller": _ay()},
    "gemi":      {"ad": "yelkenli",        "temalar": ["yolculuk", "belirsizlik", "cesaret", "uzaklaşmak", "kader"],             "sekiller": _gemi()},
    "saat":      {"ad": "köstekli saat",   "temalar": ["zaman", "geç kalmak", "sabır", "bekleyiş", "acele"],                     "sekiller": _saat()},
    "anahtar":   {"ad": "anahtar",         "temalar": ["çözüm", "sır", "kilit", "açılmak", "güven"],                             "sekiller": _anahtar()},
    "dag":       {"ad": "dağlar",          "temalar": ["zorluk", "hedef", "direnç", "yükselmek", "mesafe"],                      "sekiller": _dag()},
    "mum":       {"ad": "yanan mum",       "temalar": ["umut", "tükenmek", "ışık", "fedakârlık", "kısa ömür"],                   "sekiller": _mum()},
    "kitap":     {"ad": "açık kitap",      "temalar": ["hikâye", "geçmiş", "bilgi", "anlatmak", "sayfa çevirmek"],               "sekiller": _kitap()},
    "yagmur":    {"ad": "yağmur bulutu",   "temalar": ["hüzün", "arınma", "ağlamak", "geçicilik", "kasvet"],                     "sekiller": _yagmur()},
    "pencere":   {"ad": "pencere",         "temalar": ["bekleyiş", "içe bakış", "seyretmek", "uzak", "ev"],                      "sekiller": _pencere()},
    "kopru":     {"ad": "köprü",           "temalar": ["geçiş", "bağ", "barışmak", "aradaki mesafe", "karar"],                   "sekiller": _kopru()},
    "kalp":      {"ad": "çatlamış kalp",   "temalar": ["aşk", "kırgınlık", "kalp kırıklığı", "sevgi", "incinmek"],               "sekiller": _kalp()},
    "yildiz":    {"ad": "kayan yıldız",    "temalar": ["dilek", "umut", "kader", "an", "kaybolmak"],                             "sekiller": _yildiz()},
    "filiz":     {"ad": "filiz",           "temalar": ["başlangıç", "umut", "sabır", "iyileşmek", "büyümek"],                    "sekiller": _filiz()},
    "fener":     {"ad": "fener",           "temalar": ["yol göstermek", "arayış", "karanlıkta ışık", "rehber", "sebat"],         "sekiller": _fener()},
    "yol":       {"ad": "uzayan yol",      "temalar": ["yolculuk", "belirsiz gelecek", "ufuk", "ilerlemek", "uzaklık"],          "sekiller": _yol()},
    "kum_saati": {"ad": "kum saati",       "temalar": ["zaman", "tükeniş", "sabır", "geçen ömür", "beklemek"],                   "sekiller": _kum_saati()},
    "ayna":      {"ad": "ayna",            "temalar": ["kendine bakmak", "yüzleşmek", "kimlik", "dürüstlük", "değişim"],         "sekiller": _ayna()},
    "el":        {"ad": "açık el",         "temalar": ["bırakmak", "vermek", "yardım", "kabulleniş", "boşluk"],                  "sekiller": _el()},
}


def anahtarlar() -> list[str]:
    return list(MOTIFLER)


def katalog_metni() -> str:
    """Claude'a verilecek çizim kataloğu."""
    return "\n".join(
        f"- {k}: {v['ad']} ({', '.join(v['temalar'])})" for k, v in MOTIFLER.items()
    )


def sec(anahtar: str | None, tema: str = "", tohum: int | None = None) -> str:
    """Geçerli bir motif anahtarı döndürür; yoksa temaya, o da tutmazsa rastgele."""
    if anahtar and anahtar in MOTIFLER:
        return anahtar
    tema = (tema or "").casefold()
    if tema:
        for k, v in MOTIFLER.items():
            if any(t.casefold() in tema or tema in t.casefold() for t in v["temalar"]):
                return k
    return random.Random(tohum).choice(list(MOTIFLER))


# --------------------------------------------------------------------------
# Çizim
# --------------------------------------------------------------------------

def _renk(hex_renk: str, alfa: int = 255) -> tuple[int, int, int, int]:
    h = hex_renk.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), alfa)


def _titret(noktalar, rnd, guc):
    """Elle çizilmiş hissi için noktalara çok hafif sapma ekler."""
    return [(x + rnd.uniform(-guc, guc), y + rnd.uniform(-guc, guc)) for x, y in noktalar]


def ciz(anahtar: str, boyut: int, renk: str = "#4a3a2c") -> Image.Image:
    """Motifi şeffaf zeminli RGBA görsel olarak üretir."""
    ozel = OZEL_CIZIM_KLASORU / f"{anahtar}.png"
    if ozel.exists():
        img = Image.open(ozel).convert("RGBA")
        img.thumbnail((boyut, boyut), Image.LANCZOS)
        tuval = Image.new("RGBA", (boyut, boyut), (0, 0, 0, 0))
        tuval.paste(img, ((boyut - img.width) // 2, (boyut - img.height) // 2), img)
        return tuval

    motif = MOTIFLER[anahtar]
    B = boyut * ORNEKLEME
    tuval = Image.new("RGBA", (B, B), (0, 0, 0, 0))
    cizer = ImageDraw.Draw(tuval)
    ana = _renk(renk, 235)
    taban = max(2.0, B * 0.0062)
    rnd = random.Random(abs(hash(anahtar)) % (2 ** 31))
    guc = B * 0.0016

    for sekil in motif["sekiller"]:
        noktalar = [(x * B, y * B) for x, y in sekil["n"]]
        if sekil["t"] == "dolu":
            cizer.polygon(_titret(noktalar, rnd, guc), fill=_renk(renk, 105))
            continue
        kalinlik = max(1, int(round(taban * sekil.get("k", 1.0))))
        cizer.line(_titret(noktalar, rnd, guc), fill=ana, width=kalinlik, joint="curve")
        # uç yuvarlama
        for uc in (noktalar[0], noktalar[-1]):
            r = kalinlik / 2
            cizer.ellipse([uc[0] - r, uc[1] - r, uc[0] + r, uc[1] + r], fill=ana)

    # mürekkep dağılması: hafif bir gölge katmanı
    golge = tuval.filter(ImageFilter.GaussianBlur(B * 0.0035))
    golge.putalpha(golge.getchannel("A").point(lambda a: int(a * 0.30)))
    sonuc = Image.alpha_composite(golge, tuval)
    return sonuc.resize((boyut, boyut), Image.LANCZOS)
