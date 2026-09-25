# Yarım — karakalem defter klibi

"Yarım" şarkısı için, masadaki bir spiral defterin sayfalarında geçen karakalem klip.
Tamamı tek bir `index.html` dosyasında: HTML5 Canvas ve JavaScript ile çiziliyor. Sesler Web Audio ile
üretiliyor. Hiçbir resim ya da video dosyası kullanılmıyor.

Hazır video: [`output/yarim-klip.mp4`](output/yarim-klip.mp4) (1920×1080, 30 fps, geçici demo müzikle)

> Şarkı henüz kaydedilmediği için klipte **geçici bir demo müzik** (gitar arpeji, piyano, nakaratta
> davul) çalıyor. Kaydın hazır olunca aşağıdaki "Kendi şarkınla" adımlarıyla klibi şarkına oturt.

## Hikâye (sayfa sayfa)

| Bölüm | Sayfada olan |
|---|---|
| Giriş | Masa lambası yanar, defterin kapağı açılır. Kalem "Yarım" yazar ve boş bir sandalye çizer. |
| 1. kıta | Durmuş, camı çatlak saat: saniye ibresi ilerlemeye çalışır ama geri düşer. "Umrumda değil" denirken yerde yan yana **iki fincan** durur. Takvimde günler çizilir; en sonda gittiği gün yuvarlak içine alınır. |
| | Şehirde herkes koşuşturur. Yanındaki kişi **silgiyle silinir**, geriye kesik çizgili bir boşluk kalır. |
| Nakarat öncesi | Eski bir şenlik çizimi (bayraklar, balonlar, dans eden iki kişi) silinir. Yerine battaniyeye gömülmüş biri ve üstü örtülmüş bir ayna çizilir; kenarlar kararır. |
| 1. nakarat | Eski "biz" çizimi **tam ellerin arasından yırtılır**. Onun yarısı buruşturulup masaya atılır. Altındaki sayfaya "Yarım kaldım" yazılır. Öfkeli karalamalardan sonra kalem usulca onun elini yeniden çizer, sonra karalamalar silinir. Boş zarf ve boş konuşma balonu çizilir, sayfanın kenarına giden ayak izleri kalır. "Gittin ve bittik"te kalan yarı da, "Bittik"te sayfanın kendisi de koparılıp atılır. |
| 2. kıta | Yürür, bir kâğıdı çöpe atar, gülen arkadaşların yanında elindeki gülen maskeyi yüzüne tutar. Maske ağırlaşır, kayar ve yere düşer. Gece sokak lambaları titreyip söner. Karanlıkta silgiyle beyaz çizgilerle bir kuyu çizilir. Kamera kuyuya girer ve figür karanlıkta düşer. |
| 2. nakarat | Hızlı sayfalar: iki yırtık yarı birbirine yaklaşır ama artık uymaz. Karalarken kalemin ucu kırılır. Gökten boş zarflar yağar. Kapı onun arkasından kapanır. "Bittik"te sayfa parçalanıp yaprak gibi dökülür. |
| Köprü | Neredeyse boş sayfa: "pişman mısın?" / "Cevap: ____". Kalem çizginin üstünde bekler, bir şey yazamaz. "Canım yanıyor"da sayfa figürün göğsünden **yanar**: klipteki tek renk. |
| Final | "Öylece gittin… Yarım kaldım… Bittik." Onun buruşup düzeltilmiş yarısı sayfaya konur. Defter kapanır ama o yarı kapağın arasından taşar. Masada buruşturulmuş sayfalar birikmiştir. Lamba söner. |

## Çalıştırma

`index.html` dosyasını tarayıcıda aç ve **▶ Başlat**'a bas.
- Alttaki çubukta oynat/duraklat ve ileri/geri sarma var. BOŞLUK tuşu oynatır ya da duraklatır.
- İlk açılışta ses birkaç saniyede hazırlanır.

## Kendi şarkınla

1. **🎵 Şarkını yükle** ile kaydını seç (mp3/wav).
2. **⏱ Senkron** panelinde **Kaydı başlat**'a bas. Şarkı baştan çalar. Her satır söylenmeye başladığı
   anda BOŞLUK'a (ya da "Şimdi"ye) bas. Şarkı bitince bir kez daha bas.
3. Tüm sahneler ve geçişler satır zamanlarına bağlı olduğu için klip kendiliğinden şarkına oturur.
   Zamanlama tarayıcıda kaydedilir. **zamanlama.json indir** ile dosya olarak da alabilirsin.

## MP4 üretmek

```bash
cd yarim-klip
npm install
npx playwright install chromium          # ilk seferde gerekiyorsa

npm run render                                        # demo müzikle → output/yarim-klip.mp4
node render.js --audio sarki.mp3 --timing zamanlama.json klip.mp4   # kendi şarkınla
node render.js --audio sarki.mp3 --timing zamanlama.json --sfx 0 klip.mp4   # kâğıt/kalem sesleri olmadan
node render.js --size 720 kucuk.mp4                   # 1280x720
node render.js --range 50-90 parca.mp4                # sadece bir bölüm (hızlı önizleme)
node render.js --still 60.5,120                       # tek kare(ler) JPEG olarak
```

Betik klibi görünmez bir Chromium'da kare kare çizer (1080p'de saniyede yaklaşık 4 kare, tamamı
~25 dk). Ses ayrıca üretilir ve ffmpeg ile H.264 + AAC olarak birleştirilir. Kendi şarkınla
kullanıldığında kâğıt yırtma, kalem ve silgi sesleri şarkının altına kısık seviyede karışır
(`--sfx 0.6`; 0 = kapalı).
