"""Instagram Graph API ile paylaşım.

Gereken: Instagram hesabı "İşletme" ya da "Yaratıcı" olmalı, bir Facebook
Sayfası'na bağlı olmalı ve `instagram_content_publish` izni verilmiş uzun
ömürlü bir erişim token'ı bulunmalı.
"""

from __future__ import annotations

import os
import time

import requests

from .ayarlar import Ayarlar

TABAN = "https://graph.facebook.com"


class InstagramHatasi(RuntimeError):
    pass


class Instagram:
    def __init__(self, ayarlar: Ayarlar):
        self.surum = ayarlar.instagram.get("api_surumu", "v21.0")
        self.bekleme = float(ayarlar.instagram.get("yayin_bekleme_sn", 5))
        self.maks_deneme = int(ayarlar.instagram.get("maks_deneme", 12))
        self.kullanici_id = os.environ.get("IG_KULLANICI_ID", "").strip()
        self.token = os.environ.get("IG_ERISIM_TOKEN", "").strip()

    @property
    def hazir(self) -> bool:
        return bool(self.kullanici_id and self.token)

    def _url(self, yol: str) -> str:
        return f"{TABAN}/{self.surum}/{yol}"

    def _istek(self, yontem: str, yol: str, **veri) -> dict:
        veri["access_token"] = self.token
        try:
            yanit = requests.request(yontem, self._url(yol), data=veri, timeout=60)
        except requests.RequestException as hata:
            raise InstagramHatasi(f"Ağ hatası: {hata}") from hata

        try:
            govde = yanit.json()
        except ValueError:
            raise InstagramHatasi(f"Beklenmeyen yanıt ({yanit.status_code}): {yanit.text[:300]}")

        if "error" in govde:
            h = govde["error"]
            raise InstagramHatasi(
                f"Graph API hatası [{h.get('code')}/{h.get('error_subcode')}]: "
                f"{h.get('message')}"
            )
        if yanit.status_code >= 400:
            raise InstagramHatasi(f"HTTP {yanit.status_code}: {yanit.text[:300]}")
        return govde

    def hesap_bilgisi(self) -> dict:
        return self._istek("GET", self.kullanici_id, fields="username,name,followers_count")

    def _kap_olustur(self, gorsel_url: str, aciklama: str) -> str:
        sonuc = self._istek("POST", f"{self.kullanici_id}/media",
                            image_url=gorsel_url, caption=aciklama)
        return sonuc["id"]

    def _kabi_bekle(self, kap_id: str) -> None:
        for _ in range(self.maks_deneme):
            durum = self._istek("GET", kap_id, fields="status_code,status")
            kod = durum.get("status_code")
            if kod == "FINISHED":
                return
            if kod in ("ERROR", "EXPIRED"):
                raise InstagramHatasi(f"Medya hazırlanamadı: {durum.get('status', kod)}")
            time.sleep(self.bekleme)
        raise InstagramHatasi("Medya hazırlanırken zaman aşımı.")

    def paylas(self, gorsel_url: str, aciklama: str) -> str:
        """Görseli paylaşır ve medya kimliğini döndürür."""
        if not self.hazir:
            raise InstagramHatasi(
                "IG_KULLANICI_ID ve IG_ERISIM_TOKEN tanımlı değil. "
                "`.env` dosyanı ya da GitHub Secrets ayarlarını kontrol et."
            )
        kap_id = self._kap_olustur(gorsel_url, aciklama)
        print(f"[instagram] Medya kabı oluşturuldu: {kap_id}")
        self._kabi_bekle(kap_id)
        sonuc = self._istek("POST", f"{self.kullanici_id}/media_publish", creation_id=kap_id)
        medya_id = sonuc["id"]
        print(f"[instagram] Paylaşıldı: {medya_id}")
        return medya_id

    def token_suresi(self) -> dict | None:
        """Token'ın ne zaman dolacağını sorgular (bilgi amaçlı)."""
        try:
            yanit = requests.get(f"{TABAN}/debug_token",
                                 params={"input_token": self.token, "access_token": self.token},
                                 timeout=30)
            return yanit.json().get("data")
        except (requests.RequestException, ValueError):
            return None
