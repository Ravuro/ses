#!/usr/bin/env python3
"""Instagram günlük söz otomasyonu — komut satırı arayüzü.

    python3 calistir.py uret            # söz + görsel üret, paylaşma (deneme)
    python3 calistir.py paylas          # üret ve Instagram'da paylaş
    python3 calistir.py paylas --dilim aksam
    python3 calistir.py dogrula         # ayarları, token'ı ve bağlantıyı kontrol et
    python3 calistir.py motifler        # çizim kataloğunu listele
"""

from __future__ import annotations

import argparse
import random
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from otomasyon import ayarlar as _ayarlar          # noqa: E402
from otomasyon import barindirma, cizim, gecmis, gorsel, instagram, soz  # noqa: E402

CIKTI = Path(__file__).resolve().parent / "cikti"


def _saat_dilimi(ad: str) -> timezone:
    """zoneinfo yoksa Türkiye saati için sabit UTC+3'e döner."""
    try:
        from zoneinfo import ZoneInfo
        return ZoneInfo(ad)
    except Exception:
        return timezone(timedelta(hours=3))


def simdiki_dilim(ayarlar) -> str:
    """Şu anki saate en yakın paylaşım vaktini seçer."""
    tz = _saat_dilimi(ayarlar.paylasim.get("saat_dilimi", "Europe/Istanbul"))
    simdi = datetime.now(tz)
    dakika = simdi.hour * 60 + simdi.minute
    en_yakin, en_kucuk_fark = "sabah", 10 ** 9
    for ad, saat in ayarlar.paylasim["saatler"].items():
        s, d = (int(x) for x in saat.split(":"))
        fark = abs(dakika - (s * 60 + d))
        if fark < en_kucuk_fark:
            en_yakin, en_kucuk_fark = ad, fark
    return en_yakin


def aciklama_olustur(ayarlar, metin: str) -> str:
    p = ayarlar.paylasim
    havuz = list(p.get("etiketler", []))
    adet = min(int(p.get("etiket_sayisi", 8)), len(havuz))
    etiketler = " ".join(random.sample(havuz, adet)) if adet else ""
    return p["aciklama_sablonu"].format(
        soz=metin, imza=ayarlar.gorsel.get("imza", ""), etiketler=etiketler
    ).strip()


def uret(ayarlar, dilim: str, tema: str = "", dosya_adi: str | None = None):
    secim = soz.uret(ayarlar, dilim, tema)
    print(f"[soz] ({secim.kaynak}) {secim.metin}")
    print(f"[soz] tema: {secim.tema} | çizim: {secim.cizim}")

    resim = gorsel.olustur(ayarlar, secim.metin, secim.cizim)
    tz = _saat_dilimi(ayarlar.paylasim.get("saat_dilimi", "Europe/Istanbul"))
    ad = dosya_adi or f"{datetime.now(tz):%Y-%m-%d}-{dilim}.jpg"
    yol = gorsel.kaydet(resim, CIKTI / ad)
    print(f"[gorsel] Kaydedildi: {yol}")
    return secim, yol


def komut_uret(args, ayarlar) -> int:
    dilim = args.dilim or simdiki_dilim(ayarlar)
    secim, yol = uret(ayarlar, dilim, args.tema, args.cikti)
    print("\n--- Paylaşım açıklaması ---")
    print(aciklama_olustur(ayarlar, secim.metin))
    return 0


def komut_paylas(args, ayarlar) -> int:
    dilim = args.dilim or simdiki_dilim(ayarlar)
    ig = instagram.Instagram(ayarlar)
    if not ig.hazir:
        print("HATA: IG_KULLANICI_ID / IG_ERISIM_TOKEN tanımlı değil.", file=sys.stderr)
        return 2

    secim, yol = uret(ayarlar, dilim, args.tema, args.cikti)
    aciklama = aciklama_olustur(ayarlar, secim.metin)
    url = barindirma.yayinla(yol, ayarlar)

    try:
        medya_id = ig.paylas(url, aciklama)
    except instagram.InstagramHatasi as hata:
        print(f"HATA: {hata}", file=sys.stderr)
        return 1

    gecmis.kaydet(secim.metin, dilim, secim.cizim, yol.name, medya_id)
    print("[gecmis] Kayıt eklendi.")
    return 0


def komut_dogrula(args, ayarlar) -> int:
    sorun = 0
    print("== Ayarlar ==")
    arkaplan = ayarlar.yol(ayarlar.gorsel["arkaplan"])
    print(f"  arka plan : {arkaplan} {'✓' if arkaplan.exists() else '✗ (araclar/arkaplan_uret.py çalıştır)'}")
    if not arkaplan.exists():
        sorun += 1
    print(f"  çizim      : {len(cizim.anahtarlar())} motif")
    print(f"  paylaşım   : {ayarlar.paylasim['saatler']} ({ayarlar.paylasim['saat_dilimi']})")
    print(f"  şu anki vakit: {simdiki_dilim(ayarlar)}")

    print("\n== Claude ==")
    import os
    if os.environ.get("ANTHROPIC_API_KEY"):
        try:
            deneme = soz._claude_ile(ayarlar, simdiki_dilim(ayarlar), "deneme")
            print(f"  ✓ bağlantı çalışıyor — örnek: {deneme.metin}")
        except Exception as hata:
            print(f"  ✗ {type(hata).__name__}: {hata}")
            sorun += 1
    else:
        print("  ! ANTHROPIC_API_KEY yok — yedek söz havuzu kullanılacak.")

    print("\n== Instagram ==")
    ig = instagram.Instagram(ayarlar)
    if not ig.hazir:
        print("  ✗ IG_KULLANICI_ID / IG_ERISIM_TOKEN eksik.")
        sorun += 1
    else:
        try:
            bilgi = ig.hesap_bilgisi()
            print(f"  ✓ hesap: @{bilgi.get('username')} ({bilgi.get('followers_count', '?')} takipçi)")
            sure = ig.token_suresi()
            if sure:
                bitis = sure.get("expires_at")
                print("  token: " + ("süresiz" if bitis in (0, None)
                      else datetime.fromtimestamp(bitis, timezone.utc).strftime("%d.%m.%Y tarihinde doluyor")))
        except instagram.InstagramHatasi as hata:
            print(f"  ✗ {hata}")
            sorun += 1

    print("\n== Barındırma ==")
    b = ayarlar.barindirma
    print(f"  tür: {b['tur']}" + (f" — {b['repo']} @ {b['dal']}" if b["tur"] == "github" else ""))

    print("\n" + ("Her şey hazır." if sorun == 0 else f"{sorun} sorun var, yukarıya bak."))
    return 0 if sorun == 0 else 1


def komut_motifler(args, ayarlar) -> int:
    print(cizim.katalog_metni())
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Instagram günlük söz otomasyonu")
    alt = ap.add_subparsers(dest="komut", required=True)

    def ortak(p):
        p.add_argument("--dilim", choices=["sabah", "oglen", "aksam"],
                       help="Paylaşım vakti. Verilmezse saate göre seçilir.")
        p.add_argument("--tema", default="", help="Sözün teması (isteğe bağlı).")
        p.add_argument("--cikti", default=None, help="Görsel dosya adı.")

    ortak(alt.add_parser("uret", help="Söz ve görsel üret, paylaşma."))
    ortak(alt.add_parser("paylas", help="Üret ve Instagram'da paylaş."))
    alt.add_parser("dogrula", help="Ayarları ve bağlantıları kontrol et.")
    alt.add_parser("motifler", help="Çizim kataloğunu listele.")

    args = ap.parse_args()
    ayarlar = _ayarlar.yukle()
    return {
        "uret": komut_uret,
        "paylas": komut_paylas,
        "dogrula": komut_dogrula,
        "motifler": komut_motifler,
    }[args.komut](args, ayarlar)


if __name__ == "__main__":
    raise SystemExit(main())
