"""Paylaşılan sözlerin geçmişi: tekrar paylaşımı önler."""

from __future__ import annotations

import json
import re
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

from .ayarlar import KOK

GECMIS_DOSYASI = KOK / "veri" / "gecmis.json"


def _normalize(metin: str) -> str:
    """Karşılaştırma için sözü sadeleştirir (noktalama/büyük-küçük harf farkını yok sayar)."""
    metin = unicodedata.normalize("NFKD", metin.casefold())
    metin = "".join(k for k in metin if not unicodedata.combining(k))
    return re.sub(r"[^a-z0-9]+", " ", metin).strip()


def oku(dosya: Path = GECMIS_DOSYASI) -> list[dict]:
    if not dosya.exists():
        return []
    try:
        with open(dosya, encoding="utf-8") as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError):
        return []


def son_sozler(adet: int = 60, dosya: Path = GECMIS_DOSYASI) -> list[str]:
    return [k["soz"] for k in oku(dosya)[-adet:]]


def daha_once_paylasildi(soz: str, dosya: Path = GECMIS_DOSYASI) -> bool:
    hedef = _normalize(soz)
    return any(_normalize(k["soz"]) == hedef for k in oku(dosya))


def kaydet(soz: str, dilim: str, cizim: str, gorsel_yolu: str,
           medya_id: str | None = None, dosya: Path = GECMIS_DOSYASI) -> None:
    kayitlar = oku(dosya)
    kayitlar.append({
        "tarih": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "dilim": dilim,
        "soz": soz,
        "cizim": cizim,
        "gorsel": gorsel_yolu,
        "medya_id": medya_id,
    })
    dosya.parent.mkdir(parents=True, exist_ok=True)
    with open(dosya, "w", encoding="utf-8") as f:
        json.dump(kayitlar[-500:], f, ensure_ascii=False, indent=2)
