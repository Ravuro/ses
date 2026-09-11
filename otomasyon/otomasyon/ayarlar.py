"""Ayar dosyasını ve ortam değişkenlerini okur."""

from __future__ import annotations

import json
import os
from pathlib import Path

KOK = Path(__file__).resolve().parent.parent
AYAR_DOSYASI = KOK / "ayarlar.json"


def _env_dosyasini_yukle() -> None:
    """Varsa .env dosyasındaki değerleri ortama aktarır (harici bağımlılık yok)."""
    dosya = KOK / ".env"
    if not dosya.exists():
        return
    for satir in dosya.read_text(encoding="utf-8").splitlines():
        satir = satir.strip()
        if not satir or satir.startswith("#") or "=" not in satir:
            continue
        anahtar, _, deger = satir.partition("=")
        os.environ.setdefault(anahtar.strip(), deger.strip().strip('"').strip("'"))


class Ayarlar:
    def __init__(self, veri: dict):
        self.veri = veri

    def __getitem__(self, anahtar: str):
        return self.veri[anahtar]

    def get(self, anahtar: str, varsayilan=None):
        return self.veri.get(anahtar, varsayilan)

    @property
    def gorsel(self) -> dict:
        return self.veri["gorsel"]

    @property
    def soz(self) -> dict:
        return self.veri["soz"]

    @property
    def paylasim(self) -> dict:
        return self.veri["paylasim"]

    @property
    def barindirma(self) -> dict:
        return self.veri["barindirma"]

    @property
    def instagram(self) -> dict:
        return self.veri["instagram"]

    def yol(self, goreli: str) -> Path:
        """ayarlar.json içindeki göreli yolları mutlak yola çevirir."""
        p = Path(goreli)
        return p if p.is_absolute() else KOK / p


def yukle(dosya: Path | None = None) -> Ayarlar:
    _env_dosyasini_yukle()
    dosya = dosya or AYAR_DOSYASI
    with open(dosya, encoding="utf-8") as f:
        return Ayarlar(json.load(f))
