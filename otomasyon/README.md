# Instagram Günlük Söz Otomasyonu

Her gün **08:00**, **15:00** ve **20:00**'de (Türkiye saati) otomatik olarak:

1. Claude ile o vakte uygun, daha önce paylaşılmamış bir **Türkçe söz** üretir,
2. Sabit krem/kağıt dokulu **arka planın** üstüne sözü anlatan bir **çizim** koyar,
3. Sözü ve imzanı yerleştirip 1080×1350 görseli oluşturur,
4. **Instagram'da paylaşır.**

Her şey GitHub Actions üzerinde çalışır — bilgisayarının açık olmasına gerek yok.

---

## 1. Hızlı bakış

```
otomasyon/
├── calistir.py              # tek komutluk arayüz
├── ayarlar.json             # tüm ayarlar (saatler, renkler, imza, etiketler)
├── .env.ornek               # gizli anahtar şablonu
├── otomasyon/
│   ├── soz.py               # Claude ile söz üretimi + yedek havuz
│   ├── cizim.py             # 22 vektörel çizim motifi
│   ├── gorsel.py            # arka plan + çizim + metin yerleşimi
│   ├── instagram.py         # Instagram Graph API
│   ├── barindirma.py        # görseli herkese açık adrese taşır
│   ├── gecmis.py            # tekrar paylaşımı engeller
│   └── ayarlar.py
├── araclar/arkaplan_uret.py # sabit arka planı üretir
├── varliklar/
│   ├── arkaplan.jpg         # sabit arka plan
│   ├── cizimler/            # kendi PNG çizimlerin (isteğe bağlı)
│   └── fontlar/             # kendi fontların (isteğe bağlı)
├── veri/
│   ├── yedek_sozler.json    # Claude çalışmazsa kullanılan söz havuzu
│   └── gecmis.json          # paylaşılan sözlerin kaydı
└── cikti/                   # üretilen görseller
```

---

## 2. Kurulum

```bash
cd otomasyon
pip install -r requirements.txt
cp .env.ornek .env          # sonra .env içini doldur
python3 calistir.py uret    # deneme: paylaşmadan görsel üret
```

Üretilen görsel `cikti/` klasörüne düşer. Beğenmezsen ayarları değiştirip tekrar çalıştır.

### Komutlar

| Komut | Ne yapar |
|---|---|
| `python3 calistir.py uret` | Söz + görsel üretir, **paylaşmaz** (deneme için) |
| `python3 calistir.py paylas` | Üretir ve Instagram'da paylaşır |
| `python3 calistir.py paylas --dilim aksam --tema özlem` | Belirli vakit/tema ile |
| `python3 calistir.py dogrula` | Ayarları, Claude ve Instagram bağlantısını kontrol eder |
| `python3 calistir.py motifler` | Çizim kataloğunu listeler |
| `python3 araclar/arkaplan_uret.py --tohum 42` | Yeni bir arka plan deseni üretir |

---

## 3. Instagram bağlantısı (en zor kısım, bir kez yapılır)

Instagram'ın resmî API'si kişisel hesaplarda paylaşıma izin vermez. Sırayla:

1. **Hesabını İşletme/Yaratıcı hesabına çevir.**
   Instagram → Ayarlar → Hesap türü → "Profesyonel hesaba geç".

2. **Bir Facebook Sayfası'na bağla.**
   Instagram → Ayarlar → Sayfalar ve hesaplar → Facebook Sayfası bağla.
   (Sayfa boş olabilir, sadece bağlantı için gerekli.)

3. **Meta geliştirici uygulaması oluştur.**
   <https://developers.facebook.com/apps> → "Uygulama oluştur" → tür: **Business**.
   Ürünlerden **Instagram Graph API**'yi ekle.

4. **Erişim token'ı al.**
   <https://developers.facebook.com/tools/explorer> (Graph API Explorer):
   - Uygulamanı seç, "Kullanıcı Token'ı Oluştur".
   - Şu izinleri işaretle:
     `instagram_basic`, `instagram_content_publish`,
     `pages_show_list`, `pages_read_engagement`, `business_management`
   - Çıkan token'ı **uzun ömürlüye çevir** (Access Token Tool → "Extend Access Token").
     Kısa token 1 saat, uzun token ~60 gün yaşar.

5. **Instagram kullanıcı kimliğini bul.**
   Graph API Explorer'da sırasıyla çalıştır:
   ```
   GET /me/accounts
   GET /{sayfa_id}?fields=instagram_business_account
   ```
   Dönen `instagram_business_account.id` senin `IG_KULLANICI_ID`'in (17 haneli bir sayı).

6. **`.env` dosyasını doldur** ve kontrol et:
   ```bash
   python3 calistir.py dogrula
   ```
   Çıktıda `✓ hesap: @kullaniciadin` görüyorsan bağlantı tamam.

> **Token 60 günde bir yenilenmeli.** `dogrula` komutu token'ın ne zaman dolacağını
> söyler. Yenilemeyi unutursan paylaşımlar sessizce durmaz — Actions çalışması
> kırmızı olur ve GitHub sana e-posta gönderir.

---

## 4. Otomatik paylaşımı açmak

1. Depoda **Settings → Secrets and variables → Actions → New repository secret**
   ile üç gizli anahtar ekle:

   | Secret adı | Değeri |
   |---|---|
   | `ANTHROPIC_API_KEY` | <https://console.anthropic.com> → API anahtarı |
   | `IG_KULLANICI_ID` | 5. adımda bulduğun 17 haneli kimlik |
   | `IG_ERISIM_TOKEN` | 4. adımdaki uzun ömürlü token |

2. **Settings → Actions → General → Workflow permissions** kısmında
   **"Read and write permissions"** seçili olsun (görselin depoya yazılabilmesi için).

3. `.github/workflows/instagram.yml` zaten hazır. İlk denemeyi elle yap:
   **Actions → Instagram günlük paylaşım → Run workflow**.
   "Sadece görseli üret" kutusunu işaretlersen paylaşmadan sadece görseli üretir
   ve çalışmanın altındaki **Artifacts** bölümünden indirebilirsin.

Bundan sonrası otomatik: 05:00, 12:00 ve 17:00 UTC = 08:00, 15:00 ve 20:00 TSİ.

> GitHub'ın zamanlayıcısı yoğun saatlerde birkaç dakika gecikebilir; paylaşım
> 08:00 yerine 08:07'de düşebilir. Dakikası dakikasına paylaşım gerekiyorsa
> ücretli bir zamanlayıcı (ör. cron sunucusu) gerekir.

### Görsel nasıl barındırılıyor?

Instagram, görseli **internetten kendisi indirir**; bu yüzden görselin herkese açık
bir adresi olmalı. Varsayılan olarak görsel bu depoya commit'lenir ve
`raw.githubusercontent.com` üzerinden sunulur — bu yüzden **deponun herkese açık
olması gerekir**. Kendi sunucun varsa `ayarlar.json` içinde:

```json
"barindirma": { "tur": "manuel", "taban_url": "https://senin-site.com/gorseller" }
```

---

## 5. Kendine göre ayarlama

Her şey `ayarlar.json` içinde:

| Alan | Ne işe yarar |
|---|---|
| `gorsel.imza` | Görselin altındaki imza (`a.kadiruzn`) |
| `gorsel.metin_boyutu` | Yazı puntosu. Uzun sözlerde otomatik küçülür. |
| `gorsel.metin_rengi` / `cizim_rengi` | Renkler (hex) |
| `gorsel.cizim_boyutu` / `cizim_merkez_y` | Çizimin boyutu ve dikey konumu (0–1) |
| `gorsel.metin_font` / `imza_font` | Kendi fontunun yolu |
| `soz.uslup` | Claude'a verilen üslup talimatı |
| `soz.temalar` | Rastgele seçilen tema havuzu |
| `soz.gunluk_ton` | Sabah/öğlen/akşam sözlerinin tonu |
| `paylasim.saatler` | Paylaşım saatleri (workflow cron'u da güncellemelisin) |
| `paylasim.etiketler` | Hashtag havuzu — her paylaşımda karıştırılıp 8 tanesi seçilir |

### Arka planı değiştirmek

- **Farklı desen:** `python3 araclar/arkaplan_uret.py --tohum 77`
- **Kendi görselin:** 1080×1350 bir görseli `varliklar/arkaplan.jpg` olarak kaydet.

### Yazı tipini değiştirmek

`.ttf` dosyasını `varliklar/fontlar/` içine at, sonra `ayarlar.json`:

```json
"metin_font": "varliklar/fontlar/Poppins-SemiBold.ttf",
"imza_font":  "varliklar/fontlar/Caveat-Regular.ttf"
```

El yazısı imza için [Caveat](https://fonts.google.com/specimen/Caveat) veya
[Dancing Script](https://fonts.google.com/specimen/Dancing+Script) iyi durur.

### Çizimleri değiştirmek

22 hazır motif var (`python3 calistir.py motifler`): yürüyen kişi, yalnız ağaç,
uçan kuş, aralık kapı, hilal, yelkenli, köstekli saat, anahtar, dağlar, mum,
açık kitap, yağmur bulutu, pencere, köprü, çatlamış kalp, kayan yıldız, filiz,
fener, uzayan yol, kum saati, ayna, açık el.

Claude sözü yazdıktan sonra bu katalogdan sözü en iyi anlatanı kendisi seçer.

Kendi çizimini kullanmak istersen: şeffaf zeminli bir PNG'yi motifin adıyla
`varliklar/cizimler/` içine koy (örn. `varliklar/cizimler/yuruyen.png`).
O dosya varsa vektörel çizim yerine seninki kullanılır.

---

## 6. Bilinmesi gerekenler

- **Claude çalışmazsa otomasyon durmaz.** API anahtarı yoksa, kota biterse ya da
  ağ koparsa `veri/yedek_sozler.json` havuzundan paylaşılmamış bir söz seçilir.
- **Aynı söz iki kez paylaşılmaz.** `veri/gecmis.json` son 500 paylaşımı tutar;
  Claude'a da son 25 söz gösterilip benzerini yazmaması istenir.
- **Instagram günlük 25 API paylaşımına izin verir.** Günde 3 paylaşım bu sınırın
  çok altında.
- **Açıklama metni** söz + imza + 8 rastgele hashtag'den oluşur; şablonu
  `paylasim.aciklama_sablonu` ile değiştirebilirsin.
- **Maliyet:** Claude tarafı paylaşım başına birkaç yüz token — günde 3 paylaşımla
  aylık maliyet birkaç sentte kalır. GitHub Actions herkese açık depolarda ücretsizdir.
