# Telsiz

Android bas-konuş (push-to-talk) telsiz uygulaması.

Ses aynı anda **iki yoldan** gider ve iki yoldan da dinlenir:

| Yol | Ne zaman çalışır | Sunucu gerekir mi |
|-----|------------------|-------------------|
| **LAN** (UDP) | Aynı WiFi ağında ya da birinin hotspot'una bağlıyken | Hayır |
| **Relay** (WebSocket) | İnternet varken, uzaktaki kişilerle | Evet (`server/`) |

Kullanıcının bir şey seçmesi gerekmez. Şebeke yoksa LAN yolu tek başına
çalışmaya devam eder; internet varken ikisi birden açıktır. Aynı paket her iki
yoldan da gelirse sıra numarasıyla elenir, ses iki kez duyulmaz.

## Kurulum

APK'yı indir ve kur:

**https://raw.githubusercontent.com/Ravuro/ses/refs/heads/claude/walkie-talkie-apk-build-6orj8f/telsiz.apk**

Telefon "bilinmeyen kaynak" uyarısı verirse tarayıcıya kurulum izni ver.
Android 8.0 ve üstü gerekir.

## Kullanım

1. **Adın** ve **Kanal** (1-999) gir. Aynı kanaldakiler birbirini duyar.
2. İnternet üzerinden de konuşacaksan **Relay** adresini gir (aşağıya bak).
   Boş bırakırsan sadece WiFi/hotspot üzerinden çalışır.
3. **BAŞLAT**'a bas, mikrofon iznini ver.
4. Büyük mavi düğmeyi **basılı tutarak** konuş, bırakınca dinle.

Uygulama arka plandayken ve ekran kapalıyken de dinlemeye devam eder
(bildirimden kapatabilirsin).

### Şebeke yokken

Birisi telefonundan hotspot açar, diğerleri ona bağlanır. Hiçbir ayar
değişmeden telsiz çalışır — internet gerekmez.

## Relay sunucusu (internet için)

`server/` altında tek dosyalık bir WebSocket rölesi var. Sesi çözmez ve
kaydetmez; paketleri aynı kanaldaki diğer cihazlara olduğu gibi iletir.

Yerelde:

```bash
cd telsiz/server
npm install
npm start          # ws://<bilgisayarın-ip>:8080
```

Ücretsiz barındırma (Render): repoyu bağla, `telsiz/server` klasörünü
kök olarak seç — `render.yaml` gerisini yapar. Sonra uygulamadaki Relay
alanına `wss://<uygulama-adın>.onrender.com` yaz.

`Dockerfile` de var; Fly.io, Railway, kendi VPS'in vb. için kullanılabilir.

## Teknik

- Hiç harici bağımlılık yok: androidx, okhttp vb. kullanılmıyor. WebSocket
  istemcisi (`WebSocketClient.kt`) elde yazıldı.
- `res/` klasörü yok; arayüz koddan kuruluyor.
- 16 kHz mono ses, **IMA ADPCM** ile 4:1 sıkıştırma → ~64 kbps.
- Her paket bağımsız kodlanır: kaybolan bir UDP paketi sonrakileri bozmaz.
- 40 ms'lik parçalar; gönderen başına jitter tamponu, aynı anda konuşanlar
  karıştırılır.
- LAN tarafında hem multicast (`239.255.42.99:47771`) hem subnet broadcast
  kullanılır — router'ların ve hotspot'ların hangisini geçirdiği değişiyor.
- Konuşurken hoparlör kapanır (yarı çift yönlü), böylece geri besleme olmaz.

## Derleme

Android SDK'sı olan bir makinede normal yol:

```bash
cd telsiz
./gradlew assembleRelease
```

### SDK olmadan derleme

Bu APK, Google'ın sunucularına (`dl.google.com`, `maven.google.com`)
erişilemeyen bir ortamda derlendi — yani Android SDK, `aapt2`, `d8` ve
`androidx` indirilemiyordu. `build-nosdk.sh` bu kısıt altında çalışır:

| Normalde | Burada |
|----------|--------|
| Android SDK | `android.jar` (yalnız derleme stub'ları) |
| `aapt2` | `buildtools/AxmlEncoder.java` — manifesti ikili AXML'e çeviren kendi kodlayıcımız |
| `d8` | `dx` (Maven Central'daki repackage) |
| `apksigner` | `apksig` kütüphanesi, v2 şeması |

```bash
cd telsiz
./build-nosdk.sh        # araçları indirir, telsiz.apk üretir
```

Uygulamanın hiç bağımlılığı ve kaynak dosyası olmaması bunu mümkün kılan
şey: `aapt2` gerekmiyor, çünkü derlenecek kaynak yok.

İmza anahtarı depoda değil. Betiği yeniden çalıştırırsan yeni bir anahtar
üretilir ve imza değişir; o durumda telefondaki eski sürümü önce kaldır.
