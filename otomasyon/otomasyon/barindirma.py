"""Görseli Instagram'ın erişebileceği herkese açık bir adrese taşır.

Instagram Graph API görseli kendisi indirir; bu yüzden görselin internetten
erişilebilir bir URL'i olmalıdır. İki yöntem desteklenir:

- "github": görsel depoya commit'lenir ve raw.githubusercontent.com üzerinden sunulur
            (depo herkese açık olmalı).
- "manuel": görseli kendi sunucuna sen koyarsın; taban URL ayarlardan okunur.
"""

from __future__ import annotations

import os
import subprocess
import time
from pathlib import Path

import requests

from .ayarlar import KOK, Ayarlar


def depo_koku() -> Path:
    """Git deposunun kök dizinini bulur."""
    dizin = KOK
    for aday in [dizin, *dizin.parents]:
        if (aday / ".git").exists():
            return aday
    return KOK


def _kabuk(komut: list[str], calisma_dizini: Path) -> tuple[int, str]:
    sonuc = subprocess.run(komut, cwd=calisma_dizini, capture_output=True, text=True)
    return sonuc.returncode, (sonuc.stdout + sonuc.stderr).strip()


def _git_ile_yayinla(yol: Path, ayarlar: Ayarlar) -> str:
    b = ayarlar.barindirma
    kok = depo_koku()
    goreli = yol.resolve().relative_to(kok).as_posix()
    # GitHub Actions içinde çalışırken gerçek depo/dal bilgisi ortamdan gelir.
    dal = os.environ.get("PAYLASIM_DALI") or b["dal"]
    repo = os.environ.get("GITHUB_REPOSITORY") or b["repo"]

    if os.environ.get("BARINDIRMA_COMMIT", "1") != "0":
        _kabuk(["git", "add", "--force", goreli], kok)
        kod, cikti = _kabuk(
            ["git", "commit", "-m", f"Günlük paylaşım görseli: {yol.name}"], kok)
        if kod != 0 and "nothing to commit" not in cikti:
            print(f"[barindirma] commit uyarısı: {cikti}")
        kod, cikti = _kabuk(["git", "push", "-u", "origin", dal], kok)
        if kod != 0:
            raise RuntimeError(f"Görsel depoya gönderilemedi:\n{cikti}")

    return f"https://raw.githubusercontent.com/{repo}/{dal}/{goreli}"


def _erisilebilir_mi(url: str, deneme: int = 10, bekleme: float = 3.0) -> bool:
    """raw.githubusercontent.com birkaç saniye gecikebilir; erişilene kadar bekler."""
    for i in range(deneme):
        try:
            yanit = requests.get(url, timeout=20, headers={"Range": "bytes=0-0"})
            if yanit.status_code in (200, 206):
                return True
        except requests.RequestException:
            pass
        if i < deneme - 1:
            time.sleep(bekleme)
    return False


def yayinla(yol: Path, ayarlar: Ayarlar) -> str:
    """Görseli erişilebilir hâle getirir ve herkese açık URL'ini döndürür."""
    tur = ayarlar.barindirma.get("tur", "github")

    if tur == "manuel":
        taban = (os.environ.get("GORSEL_TABAN_URL")
                 or ayarlar.barindirma.get("taban_url", "")).rstrip("/")
        if not taban:
            raise RuntimeError(
                "Manuel barındırma seçili ama taban URL yok. "
                "GORSEL_TABAN_URL ortam değişkenini ya da ayarlar.json -> barindirma.taban_url alanını doldur."
            )
        url = f"{taban}/{yol.name}"
    elif tur == "github":
        url = _git_ile_yayinla(yol, ayarlar)
    else:
        raise RuntimeError(f"Bilinmeyen barındırma türü: {tur}")

    print(f"[barindirma] Görsel adresi: {url}")
    if not _erisilebilir_mi(url):
        raise RuntimeError(
            f"Görsele internetten erişilemiyor: {url}\n"
            "GitHub barındırması için deponun herkese açık olması gerekir."
        )
    return url
