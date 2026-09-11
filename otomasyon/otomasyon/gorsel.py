"""Paylaşılacak görseli oluşturur: sabit arka plan + çizim + söz + imza."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

from . import cizim
from .ayarlar import KOK, Ayarlar

FONT_KLASORU = KOK / "varliklar" / "fontlar"

# Kendi fontunu `varliklar/fontlar/` içine atarsan otomatik bulunur.
METIN_ADAYLARI = [
    "Poppins-SemiBold.ttf", "Montserrat-SemiBold.ttf", "Nunito-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
]
IMZA_ADAYLARI = [
    "Caveat-Regular.ttf", "DancingScript-Regular.ttf", "GreatVibes-Regular.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSerif-Italic.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf",
    "/usr/share/fonts/truetype/freefont/FreeSerifItalic.ttf",
]


def _renk(hex_renk: str, alfa: int = 255) -> tuple[int, int, int, int]:
    h = hex_renk.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), alfa)


def _font_bul(adaylar: list[str], elle: str = "") -> str:
    """Sırayla: ayarlardaki font, varliklar/fontlar/, sistem fontları."""
    if elle:
        p = Path(elle)
        p = p if p.is_absolute() else KOK / p
        if p.exists():
            return str(p)
        print(f"[gorsel] Font bulunamadı: {p}; varsayılana dönülüyor.")
    for aday in adaylar:
        p = Path(aday)
        if not p.is_absolute():
            p = FONT_KLASORU / aday
        if p.exists():
            return str(p)
    raise FileNotFoundError("Kullanılabilir font bulunamadı.")


def _font(yol: str, boyut: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(yol, boyut)


def _sar(metin: str, font, cizer, maks_genislik: int) -> list[str]:
    """Metni verilen genişliğe göre satırlara böler."""
    satirlar: list[str] = []
    for paragraf in metin.split("\n"):
        kelimeler = paragraf.split()
        if not kelimeler:
            satirlar.append("")
            continue
        satir = kelimeler[0]
        for kelime in kelimeler[1:]:
            aday = f"{satir} {kelime}"
            if cizer.textlength(aday, font=font) <= maks_genislik:
                satir = aday
            else:
                satirlar.append(satir)
                satir = kelime
        satirlar.append(satir)
    return satirlar


def _dengele(satirlar: list[str]) -> list[str]:
    """Son satırda tek kelime kalmasını önler (daha dengeli görünsün diye)."""
    if len(satirlar) >= 2 and len(satirlar[-1].split()) == 1 and len(satirlar[-2].split()) > 2:
        onceki = satirlar[-2].split()
        satirlar[-1] = f"{onceki[-1]} {satirlar[-1]}"
        satirlar[-2] = " ".join(onceki[:-1])
    return satirlar


def _metni_sigdir(metin: str, cizer, font_yolu: str, baslangic_boyut: int,
                  maks_genislik: int, maks_yukseklik: int, satir_araligi: float):
    """Metin kutuya sığana kadar punto düşürür."""
    boyut = baslangic_boyut
    while boyut > 20:
        font = _font(font_yolu, boyut)
        satirlar = _dengele(_sar(metin, font, cizer, maks_genislik))
        satir_yuksekligi = int(boyut * satir_araligi)
        if len(satirlar) * satir_yuksekligi <= maks_yukseklik and len(satirlar) <= 6:
            return font, satirlar, satir_yuksekligi
        boyut -= 2
    font = _font(font_yolu, boyut)
    return font, _dengele(_sar(metin, font, cizer, maks_genislik)), int(boyut * satir_araligi)


def olustur(ayarlar: Ayarlar, metin: str, cizim_anahtari: str) -> Image.Image:
    g = ayarlar.gorsel
    en, boy = g["genislik"], g["yukseklik"]

    arkaplan_yolu = ayarlar.yol(g["arkaplan"])
    if not arkaplan_yolu.exists():
        raise FileNotFoundError(
            f"Arka plan yok: {arkaplan_yolu}\n"
            "Önce `python3 araclar/arkaplan_uret.py` komutunu çalıştır."
        )
    tuval = Image.open(arkaplan_yolu).convert("RGB").resize((en, boy), Image.LANCZOS)
    tuval = tuval.convert("RGBA")

    # --- Çizim ---
    cizim_boyutu = int(g["cizim_boyutu"])
    resim = cizim.ciz(cizim_anahtari, cizim_boyutu, g["cizim_rengi"])
    cx = (en - cizim_boyutu) // 2
    cy = int(boy * g["cizim_merkez_y"]) - cizim_boyutu // 2
    # kağıda oturması için çok hafif bir gölge
    golge = Image.new("RGBA", tuval.size, (0, 0, 0, 0))
    golge.paste(resim, (cx + 3, cy + 4), resim)
    golge = golge.filter(ImageFilter.GaussianBlur(6))
    golge.putalpha(golge.getchannel("A").point(lambda a: int(a * 0.18)))
    tuval = Image.alpha_composite(tuval, golge)
    tuval.paste(resim, (cx, cy), resim)

    katman = Image.new("RGBA", tuval.size, (0, 0, 0, 0))
    cizer = ImageDraw.Draw(katman)

    # --- Söz ---
    metin_font_yolu = _font_bul(METIN_ADAYLARI, g.get("metin_font", ""))
    maks_genislik = int(en * g["metin_genislik_orani"])
    maks_yukseklik = int(boy * (g["imza_y"] - g["metin_baslangic_y"])) - int(boy * 0.05)
    font, satirlar, satir_yuksekligi = _metni_sigdir(
        metin, cizer, metin_font_yolu, int(g["metin_boyutu"]),
        maks_genislik, maks_yukseklik, float(g["satir_araligi"]),
    )

    toplam = len(satirlar) * satir_yuksekligi
    ust = int(boy * g["metin_baslangic_y"]) - toplam // 2 + int(boy * 0.06)
    metin_rengi = _renk(g["metin_rengi"])
    for i, satir in enumerate(satirlar):
        y = ust + i * satir_yuksekligi
        cizer.text((en // 2, y), satir, font=font, fill=metin_rengi, anchor="ma")

    # --- İmza ---
    imza = g.get("imza", "").strip()
    if imza:
        imza_font = _font(_font_bul(IMZA_ADAYLARI, g.get("imza_font", "")), int(g["imza_boyutu"]))
        cizer.text((en // 2, int(boy * g["imza_y"])), imza, font=imza_font,
                   fill=_renk(g["imza_rengi"], 225), anchor="ma")

    # mürekkebin kağıda hafifçe dağılması
    yumusak = katman.filter(ImageFilter.GaussianBlur(1.6))
    yumusak.putalpha(yumusak.getchannel("A").point(lambda a: int(a * 0.35)))
    tuval = Image.alpha_composite(tuval, yumusak)
    tuval = Image.alpha_composite(tuval, katman)
    return tuval.convert("RGB")


def kaydet(gorsel: Image.Image, yol: Path) -> Path:
    yol.parent.mkdir(parents=True, exist_ok=True)
    gorsel.save(yol, quality=94, subsampling=0, optimize=True)
    return yol
