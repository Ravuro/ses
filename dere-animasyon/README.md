# Dere Yüzüşü Animasyonu

Bir kurbanın derenin bir ucundan (BAŞLANGIÇ) diğer ucuna (VARIŞ) yüzüşünü kuşbakışı gösteren,
tamamen JavaScript (HTML5 Canvas) ile çizilmiş animasyon.

Hazır çıktı: [`output/kurban-dere-yuzusu.mp4`](output/kurban-dere-yuzusu.mp4) (1280×720, 30 fps, 14 sn, H.264)

## Dosyalar

| Dosya | Görev |
|---|---|
| `index.html` | Canvas sayfası. Tarayıcıda açınca animasyon döngüde oynar. |
| `animation.js` | Sahne: kıvrımlı dere, akan su, kıyılar, ağaçlar, serbest stil yüzen kişi, dalga izi, bilgi paneli. |
| `render.js` | Sayfayı başsız Chromium'da açar, 420 kareyi tek tek çizdirip ffmpeg ile MP4'e kodlar. |

## MP4'ü yeniden üretmek

```bash
cd dere-animasyon
npm install
npx playwright install chromium   # ilk seferde gerekiyorsa
npm run render                    # → output/kurban-dere-yuzusu.mp4
npm run render -- baska-ad.mp4    # farklı bir dosyaya yazmak için
```

Süre, hız, dere uzunluğu (metre) ve yüzücü boyutu `animation.js` dosyasının en üstündeki
sabitlerden (`DURATION`, `T_START`, `T_END`, `CREEK_METERS`, `SCALE`) değiştirilebilir.
