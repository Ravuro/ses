# Yarım — karakalem defter klibi (CLAUDE.md)

Bu klasör, "Yarım" şarkısı için JavaScript/Canvas ile üretilen bir müzik klibi projesi.
Sahibi Türkçe konuşuyor: cevaplar, arayüz metinleri ve kod yorumları Türkçe olsun.

## Dosyalar

- `index.html`: Projenin tamamı tek dosyada (çizim motoru, sahneler, ses, tarayıcı oynatıcısı).
  Çift tıklayınca tarayıcıda açılır.
- `render.js`: Klibi başsız Chromium'da (Playwright) kare kare çizer ve ffmpeg (`ffmpeg-static`) ile MP4 yapar.
- `package.json`: Bağımlılıklar `playwright@1.56.1` ve `ffmpeg-static`.
- `README.md`: Kullanıcı için açıklama.
- Şarkı dosyası (mp3/wav) bu klasöre konuldu. Adını klasörü listeleyerek bul.

## Kurulum (Windows)

```powershell
npm install
npx playwright install chromium
node render.js --still 60,120          # output\still-60.jpg ... (hızlı kontrol)
node render.js --range 50-90 test.mp4  # kısa bölüm
node render.js --audio "<şarkı>.mp3" --timing zamanlama.json output\yarim-klip.mp4
```

Tam 1080p render 25–40 dakika sürer. Tam render'a geçmeden önce `--still` ve `--range` ile kontrol et.

## Mimari (index.html)

- **Zamanlama:** `CUE_ORDER` / `LYRICS` her söz satırının kimliğini ve metnini tutar
  (`v1_1…v1_4, pc_1…pc_3, c1_1…c1_6, v2_1…v2_5, c2_1…c2_6, br_1…br_3, out_1…out_3, end`).
  `buildStory(C)` bütün sahneleri bu satır zamanlarından (saniye) kurar. Yani zamanlama
  değişince klip kendiliğinden yeniden oturur. `end` şarkının bitişidir. Klip süresi
  `S.duration = max(C.end + 4.5, C.out_3 + 9)`. Şarkı süresine eşit olması için bunu değiştir.
- **Zamanlamanın kaynakları:** `render.js --timing file.json` (`{"cues":{...}}`), tarayıcıda
  "⏱ Senkron" aracı (BOŞLUK ile satır işaretleme, localStorage'a kaydeder) ya da `defaultCues()`
  (72 BPM demoya göre). `sanitizeCues` zamanların artan sırada olmasını ister.
- **Çizim işlemleri (ops):** `Builder.seq(specs, t0, t1)` çizimleri zaman aralığına yayar.
  Tür `stroke | hatch | text | erase | tone | hover` olabilir. Grafit dokusu `TEX.graphite` desenidir.
  Silgi, sayfanın grafit katmanında `destination-out` ile çalışır.
  `b.pre()` sayfayı önceden çizilmiş hâlde getirir. `b.dyn(t0, t1, 'g'|'top', fn)` her karede
  çizilen hareketli öğeleri ekler.
- **Figürler:** `fk(pose)` basit bir iskelet kurar (açılar derece cinsinden: 0 yukarı, 90 sağ, 180 aşağı;
  `lTarget/rTarget` ile iki kemikli IK). `figureShapes` kontur, saç ve tarama bölgelerini üretir.
  `figureSpecs` bunları op'a çevirir; `knock: true` arkadaki çizgileri maskeler.
  `drawFigureNow` hareketli figürleri çizer. **Şu anki figürler çubuk adama yakın. Bunlar yeniden tasarlanacak.**
- **Kâğıt parçaları:** `flip`, `tearFull`, `tearHalf`, shred (`fall`), `drawPaperPiece`. Bunlar
  `getSnapshot` ile alınan sayfa görüntüsünü kullanır (LRU önbellek).
- **`renderFrame(R, S, t)` yalnızca `t`'ye bağlı saf bir fonksiyondur:** ileri/geri sarma ve kare kare
  render bu sayede çalışır. Bunu bozma: durum biriktirme, tüm rastgelelik `mulberry32` ile tohumlansın.
- **Ses:** `renderAudio` 30 sn'lik parçaları paralel olarak `OfflineAudioContext`'te işler.
  `buildMusic` geçici demo müziği üretir. `S.sfx` kâğıt, kalem ve silgi efektlerini tutar.
  Kendi şarkı kullanılırken demo müzik kapatılır (`music: false`), efektler kısık karışır (`--sfx 0.6`).
- **Render arayüzü:** `window.KLIP.init({scale, cues})`, `frame(i)`, `audio(opts)` (render.js kullanır).

## Bilinen dersler

- Canlı oynatmada ses saati başta negatif `t` verebiliyor. Bu yüzden `frame()` `t`'yi 0'a kırpıyor.
  Her dizi indeksinde negatif değerlere dikkat et.
- Kâğıt ve film greni sıkıştırmayı pahalı yapıyor. CRF 18 ile dosya ~500 MB olur, CRF 29–30 ile ~40 MB.
- Google Fonts'taki Caveat fontunu `render.js` Node `fetch` üzerinden getiriyor. İnternet yoksa yedek font kullanılır.

## ŞU ANKİ GÖREV: v2

1. **Şarkıya tam senkron.** Şarkı 3:34.21 sürüyor, eski klip 3:31 ve satırlar oturmuyor.
   - Satır başlarını sesten bul. Önerilen yol: Python `faster-whisper` (ya da `stable-ts`),
     `language="tr"`, `word_timestamps=True`, model `medium` veya `large-v3`. Tanınan kelimeleri
     `LYRICS` satırlarına hizala (bulanık eşleme) ve `zamanlama.json` üret.
   - Otomatik sonucu kullanıcıya satır satır tablo olarak göster ve onayını al. Kusur varsa tarayıcıdaki
     Senkron aracıyla düzeltilebilir.
   - Sahne geçişlerini, yırtılmaları ve kamera vurgularını şarkının vuruşlarına ve bölüm girişlerine oturt
     (tempo ve vuruş analizi için `librosa` kullanılabilir).
   - `S.duration` şarkı süresine eşit olsun. Final (defter kapanır, lamba söner) şarkının son notasıyla bitsin.
   - Demo müzik tamamen kalksın; MP4'te şarkının kendisi çalsın.
2. **Daha melankolik, duygu dolu ve olgun bir görsel dil.** Kullanıcıya göre şu anki hâl fazla
   "çocuk klibi / çizgi film" gibi.
   - Karakterler belirgin ve gerçekçi oranlı karakalem olsun: yüz hatları, göz ve kaş ifadesi, saç
     kütlesi, kıyafet kıvrımları, gölgeleme (tarama ve blend). İki ana karakterin (anlatıcı, giden kişi)
     tutarlı bir tasarımı olsun.
   - Işık daha loş, kamera daha yavaş, daha yakın çekimler (yüz, eller, gözler), daha sakin geçişler.
   - Fazla sevimli ya da komik öğeleri azalt: "ha ha" yazıları, gülen yüzlü maskenin basitliği, oyuncak gibi
     zarf yağmuru. Maske, zarf ve kapı gibi imgeler kalabilir ama daha ağır ve gerçekçi çizilsin.
   - Güçlü bulunan fikirleri koru: el ele çizimin tam ellerin arasından yırtılması ("biz" yazısının ikiye
     bölünmesi), silinen boşluk, sönen lambalar, kuyuda düşüş, köprüde sayfanın göğüsten yanması (tek renk),
     finalde onun yarısının kapanan defterden taşıması, masada biriken buruşuk sayfalar.
   - Çelişkiyi söylemek yerine göster (ör. "umrumda değil" derken yerde iki fincan). Her sahnede zorlama.
3. Önce 5–8 anahtar kareyi `--still` ile üretip kullanıcıya göster ve yönü onaylat. Sonra bölüm bölüm
   `--range` ile ilerle. En son tam render'ı al.
