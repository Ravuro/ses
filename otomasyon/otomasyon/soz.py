"""Günlük sözü üretir.

Öncelik: Claude (Anthropic Messages API). API anahtarı yoksa ya da istek
başarısız olursa `veri/yedek_sozler.json` havuzundan daha önce paylaşılmamış
bir söz seçilir — böylece otomasyon hiçbir zaman elleri boş dönmez.
"""

from __future__ import annotations

import json
import random
from dataclasses import dataclass
from datetime import date

from . import cizim, gecmis
from .ayarlar import KOK, Ayarlar

YEDEK_DOSYASI = KOK / "veri" / "yedek_sozler.json"

SISTEM = """Sen Türkçe yazan bir söz yazarısın. Instagram'da paylaşılmak üzere \
tek bir özgün söz yazarsın.

Kurallar:
- Sadece Türkçe yaz. İngilizce kelime kullanma.
- Tek cümle; gerekiyorsa en fazla iki kısa cümle.
- {maks} karakteri geçme.
- Klişelerden kaçın: "hayat devam ediyor", "kendine iyi bak", "her şey güzel olacak" gibi \
tükenmiş kalıpları yazma.
- Ünlem işareti, emoji, tırnak, hashtag ve imza kullanma. Sadece sözün kendisini yaz.
- Ünlü birinin sözünü alıntılama; sözü sen yaz.
- Üslup: {uslup}

Sözü yazdıktan sonra, sözü en iyi anlatan çizimi aşağıdaki katalogdan seç.

Çizim kataloğu:
{katalog}"""

KULLANICI = """Bugün {tarih}, paylaşım vakti: {dilim_adi} ({saat}).
Bu vaktin tonu: {ton}

İstenen tema: {tema}

Son paylaşılan sözler (bunlara benzemesin, konuları tekrarlanmasın):
{gecmis}

Bu vakit için tek bir söz yaz."""

SEMA = {
    "type": "object",
    "properties": {
        "soz": {"type": "string", "description": "Paylaşılacak Türkçe söz."},
        "tema": {"type": "string", "description": "Sözün ana teması, tek kelime ya da kısa öbek."},
        "cizim": {"type": "string", "enum": cizim.anahtarlar(),
                  "description": "Sözü en iyi anlatan çizim anahtarı."},
    },
    "required": ["soz", "tema", "cizim"],
    "additionalProperties": False,
}


@dataclass
class Soz:
    metin: str
    tema: str
    cizim: str
    kaynak: str  # "claude" | "yedek"


def _gecmis_metni(adet: int = 25) -> str:
    sozler = gecmis.son_sozler(adet)
    return "\n".join(f"- {s}" for s in sozler) if sozler else "- (henüz paylaşım yok)"


def _temizle(metin: str) -> str:
    metin = metin.strip().strip('"').strip("“”").strip()
    return " ".join(metin.split())


def _claude_ile(ayarlar: Ayarlar, dilim: str, tema: str) -> Soz:
    import anthropic  # yerel içe aktarma: anahtar yoksa modül hiç gerekmesin

    a = ayarlar.soz
    saatler = ayarlar.paylasim["saatler"]
    dilim_adlari = {"sabah": "sabah", "oglen": "öğlen", "aksam": "akşam"}

    istemci = anthropic.Anthropic()
    yanit = istemci.messages.create(
        model=a["model"],
        max_tokens=2000,
        system=SISTEM.format(maks=a["maks_karakter"], uslup=a["uslup"],
                             katalog=cizim.katalog_metni()),
        messages=[{
            "role": "user",
            "content": KULLANICI.format(
                tarih=date.today().strftime("%d.%m.%Y"),
                dilim_adi=dilim_adlari.get(dilim, dilim),
                saat=saatler.get(dilim, ""),
                ton=a["gunluk_ton"].get(dilim, ""),
                tema=tema,
                gecmis=_gecmis_metni(),
            ),
        }],
        output_config={"format": {"type": "json_schema", "schema": SEMA}},
    )
    if yanit.stop_reason == "refusal":
        raise RuntimeError("Model isteği reddetti.")

    ham = next(b.text for b in yanit.content if b.type == "text")
    veri = json.loads(ham)
    metin = _temizle(veri["soz"])
    if not metin:
        raise RuntimeError("Model boş söz döndürdü.")
    return Soz(metin, veri.get("tema", tema), cizim.sec(veri.get("cizim"), veri.get("tema", tema)), "claude")


def _yedekten(tema: str = "") -> Soz:
    with open(YEDEK_DOSYASI, encoding="utf-8") as f:
        havuz = json.load(f)
    kullanilmamis = [k for k in havuz if not gecmis.daha_once_paylasildi(k["soz"])]
    aday = random.choice(kullanilmamis or havuz)
    return Soz(_temizle(aday["soz"]), aday.get("tema", tema),
               cizim.sec(aday.get("cizim"), aday.get("tema", "")), "yedek")


def uret(ayarlar: Ayarlar, dilim: str, tema: str = "", deneme: int = 3) -> Soz:
    """Bu vakit için bir söz üretir; tekrar çıkarsa yeniden dener."""
    temalar = ayarlar.soz["temalar"]
    tema = tema or random.choice(temalar)

    for _ in range(deneme):
        try:
            aday = _claude_ile(ayarlar, dilim, tema)
        except Exception as hata:  # anahtar yok, ağ hatası, kota vb.
            print(f"[soz] Claude kullanılamadı ({type(hata).__name__}: {hata}); yedek havuza geçiliyor.")
            return _yedekten(tema)
        if not gecmis.daha_once_paylasildi(aday.metin):
            return aday
        print("[soz] Aynı söz daha önce paylaşılmış, yeniden üretiliyor.")
        tema = random.choice(temalar)

    return _yedekten(tema)
