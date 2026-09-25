# Zıpzıp'ın Dere Macerası

Sevimli yeşil kurbağa Zıpzıp'ın dereye atlayıp yolda balık, ördek, kaplumbağa, yusufçuk ve
su samuruyla karşılaştığı, 45 saniyelik 16:9 çocuk animasyonu. Her şey tek bir `index.html`
içinde: HTML5 Canvas, JavaScript, CSS ve Web Audio ile kodla üretilen sesler/müzik.
Hiçbir resim ya da ses dosyası kullanılmaz.

Hazır video: [`output/zipzip-dere-macerasi.mp4`](output/zipzip-dere-macerasi.mp4) (1920×1080, 30 fps, 45 sn, sesli)

## Çalıştırma

1. `index.html` dosyasına çift tıklayın, animasyon tarayıcıda hemen başlar.
2. Tarayıcılar sesi otomatik başlatmaya izin vermediği için sağ üstteki **🔇 Sesi Aç** düğmesine
   (ya da ekrana) bir kez dokunun. `M` tuşu sesi açıp kapatır.
3. Video bitince **↻ Tekrar İzle** düğmesi çıkar.

Canvas pencere boyutuna uyar; içerik her zaman 16:9 kalır, kalan alan koyu kenarlıkla doldurulur
(telefon dikey/yatay ve masaüstünde çalışır). İnternet yoksa Fredoka yazı tipi yerine sistem
yazı tipi kullanılır, animasyon yine çalışır.

## Sahneler

| Süre | Sahne |
|---|---|
| 0–5 sn | Dere kenarı: Zıpzıp sağa sola bakar, kameraya dönüp gülümser |
| 5–8 sn | Geri çekilir, bacaklarını hazırlar, **ZIP!** diye suya atlar (sıçrama, halka, baloncuk) |
| 8–14 sn | Kollar ve bacaklarla yüzme, kamera yandan takip eder, arka plan kayar |
| 14–19 sn | Balık kurbağanın etrafında tur atar, kuyruk sallar, birlikte yüzerler |
| 19–24 sn | Ördek "Vak vak!" der, kurbağa komik şekilde göz kırpar, ördek kanat çırpar |
| 24–29 sn | Kurbağa yavaş kaplumbağaya ayak uydurur, kaplumbağa başını kaldırıp gülümser |
| 29–33 sn | Yusufçuk kurbağanın üstünde daire çizer, kamera yukarı açılır |
| 33–39 sn | Su samuru takla atar, kurbağa taklit eder ve başı döner |
| 39–45 sn | Karşı kıyıya çıkar, silkelenir, kameraya dönüp el sallar; arkadaşları arkada |

## Kod yapısı (`index.html`)

| Bölüm | Görev |
|---|---|
| `SoundManager` | Web Audio ile efektler (zıplama, sıçrama, baloncuk, vak vak, kuş, akıntı…) ve döngüsel müzik |
| `Camera` | Hedefe yumuşak kayma ve yakınlaşma (pan, zoom, takip) |
| `Background` | Gökyüzü, gülen güneş, bulutlar, tepeler, ağaçlar, çayır; parallax katmanlar |
| `River` | Dalgalı su yüzeyi, taban, su bitkileri, nilüferler, kıyılar |
| `ParticleSystem` | Damla, baloncuk, halka, kalp, yıldız, hız çizgisi |
| `Frog`, `Fish`, `Duck`, `Turtle`, `Dragonfly`, `Otter` | Her biri `position`, `velocity`, animasyon durumu, `update()` ve `draw()` içerir |
| `SceneManager` | Sahne yönetmenleri, zamanlanmış olaylar, konuşma balonları, başlık ve geçişler |
| `App` | `requestAnimationFrame` döngüsü (sabit 30 FPS adım), ekran uyumu, düğmeler |

## MP4 olarak kaydetme

### Yöntem 1: Hazır render betiği (önerilen)

Bu betik videoyu kare kare üretir. Bilgisayar yavaş olsa bile takılma olmaz, ses görüntüyle tam
senkron kalır.

```bash
cd kurbaga-animasyon
npm install
npx playwright install chromium        # ilk seferde
npm run render                         # → output/zipzip-dere-macerasi.mp4 (1920x1080)
node render.js --size 720 kucuk.mp4    # 1280x720
node render.js --still 20.5            # tek kare önizleme (JPEG)
```

`render.js` sayfayı görünmez bir Chromium'da açar ve 1350 kareyi sırayla çizdirir. Sesi
`OfflineAudioContext` ile ayrıca üretir, ses seviyesini dengeler ve ffmpeg ile ikisini
H.264 + AAC olarak birleştirir. (ffmpeg, `ffmpeg-static` paketiyle birlikte gelir.)

### Yöntem 2: Ekran kaydı

- **OBS Studio** (ücretsiz): *Tarayıcı* ya da *Pencere Yakalama* kaynağı ekleyin. Çıkışı
  1920×1080, 30 FPS ve MP4 olarak ayarlayın; masaüstü sesini de açın. Sayfayı tam ekran yapıp
  (F11) kaydedin.
- Windows'ta **Win + G** (Xbox Game Bar), macOS'ta **Cmd + Shift + 5** de iş görür (macOS'ta sistem
  sesi için ek araç gerekir).
- Kayıt WebM çıkarsa MP4'e dönüştürmek için:
  `ffmpeg -i kayit.webm -c:v libx264 -pix_fmt yuv420p -c:a aac kayit.mp4`
