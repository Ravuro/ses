# Telsiz

Android bas-konuş (push-to-talk) telsiz uygulaması.

Ses aynı anda **iki yoldan** gider ve iki yoldan da dinlenir:

| Yol | Ne zaman çalışır | Sunucu gerekir mi |
|-----|------------------|-------------------|
| **LAN** (UDP) | Aynı yerel ağda: router, hotspot ya da Wi-Fi Direct | Hayır |
| **Relay** (WebSocket) | İnternet varken, uzaktaki kişilerle | Evet (`server/`) |

Kullanıcının bir şey seçmesi gerekmez. İnternet yoksa LAN yolu tek başına
çalışmaya devam eder. Aynı paket her iki yoldan da gelirse sıra numarasıyla
elenir, ses iki kez duyulmaz.

## Hiç ağ yokken — ne WiFi ne şebeke

Uygulamadaki **TELSİZ AĞI KUR** düğmesi bir Wi-Fi Direct grubu açar: telefon
kendi kablosuz ağını yayınlar. Router, SIM kart, internet — hiçbiri gerekmez,
sadece WiFi'nin açık olması yeter (bir ağa bağlı olması gerekmiyor).

Diğerleri bu ağa WiFi ayarlarından bağlanır:

```
Ağ adı : DIRECT-Telsiz
Parola : telsiz1234
```

Ad ve parola sabit; kuran kişinin kimseye bir şey söylemesine gerek yok.
(Android 9 ve altında sistem rastgele üretir, uygulama ekranda gösterir.)

Grup kurulunca sistem yeni bir ağ arayüzü açar ve ses taşıyıcısı onu kendi
bulur — ayrıca bir şey yapman gerekmiyor.

## Kurulum

APK'yı indir ve kur:

**https://raw.githubusercontent.com/Ravuro/ses/refs/heads/claude/walkie-talkie-apk-build-6orj8f/telsiz.apk**

Telefon "bilinmeyen kaynak" uyarısı verirse tarayıcıya kurulum izni ver.
Android 8.0 ve üstü gerekir.

## Kullanım

1. **Adın** ve **Kanal** (1-999) gir. Aynı kanaldakiler birbirini duyar.
2. **BAŞLAT**'a bas, mikrofon iznini ver.
4. Büyük düğmeyi **basılı tutarak** konuş, bırakınca dinle. **Ses yükseltme
   tuşunu** basılı tutmak da aynı işi yapar — ekran kapalıyken bile. Ses
   kısma tuşu ses ayarı olarak kalır. İstemezsen ayarlardan kapatabilirsin.

### Kaçırdığını tekrar dinle

Gelen ses konuşma parçalarına ayrılarak bellekte tutuluyor: araya 900 ms'den
uzun sessizlik girince o konuşma bitmiş sayılıp kenara konuyor. **SON
KONUŞMAYI ÇAL** düğmesi onu yeniden çalıyor. Son 20 saniye saklanıyor;
daha uzun konuşmalarda konuşmanın sonu tutuluyor (halka tampon), çünkü
"ne dedi?" diye sorulduğunda istenen şey sonu.

Tekrar, ayrı bir sahte gönderenin kuyruğundan geçiyor — yani canlı ses
gelirse üstüne karışıyor, bir şey kaçırmıyorsun.

### Arka planda donma (Xiaomi, Oppo, Vivo, Huawei)

Bu arayüzler arka plandaki uygulamayı donduruyor; donduğunda ne ses tuşu
duyuluyor ne de konuşma gidiyor. Uygulama bunu algılayıp uyarı gösteriyor ve
pil kısıtlamasını kaldıracak ayarı açıyor. Xiaomi/Poco'da ayrıca
"Otomatik başlatma" izni ve son uygulamalar ekranında kilitleme gerekiyor —
bunlar standart bir arayüzden açılamıyor, kullanıcının elle yapması lazım.

### Ekran kapalıyken ses tuşu

Ekran kapandığında tuş olayları uygulamaya gelmez. Uygulama bunun için bir
medya oturumu açıp sesi "uzak" olarak bildiriyor; böylece ses tuşları
oturuma düşüyor (`KeyPtt.kt`).

Sistem bas/bırak değil yalnızca "ses artır" olayı gönderdiği için basılı
tutma buradan çıkarılıyor: ilk olayda konuşma başlıyor, olaylar kesildikten
~0.55 sn sonra bitiyor. Yani bıraktıktan sonra kısa bir kuyruk kalıyor.
Tuş takılı kalırsa mikrofon 60 sn sonra kendiliğinden kapanıyor.

Bu yol cihazdan cihaza değişebilir: başka bir uygulama müzik çalıyorsa ses
tuşları ona gidebilir. Ekran açıkken çalışan yol bundan etkilenmiyor.

Ekran iki durumlu: kapalıyken ayarlar görünür, açıldığında yerine bağlantı
durumu ve kanaldakiler gelir.

Uygulama arka plandayken ve ekran kapalıyken de dinlemeye devam eder
(bildirimden kapatabilirsin).

### Ağ kopunca

Uygulama ağ arayüzlerini sürekli izler. WiFi kopup geri gelse, hotspot açılsa
ya da Wi-Fi Direct grubu kurulsa, soketi kendiliğinden yeniden kurar ve ses
akmaya devam eder.

## Menzil

Telefon telsizi, gerçek bir telsizin menziline yazılımla ulaşamaz. Fark
donanımda ve fizikte:

| | Telefon (Wi-Fi Direct) | PMR telsiz |
|---|---|---|
| Frekans | 2.4 GHz | 446 MHz |
| Verici gücü | ~0.1 W | 0.5-5 W |
| Anten | gövde içinde, birkaç mm | ~17 cm çeyrek dalga |

Frekans farkı tek başına ~15 dB, güç farkı ~13 dB. Toplam ~30 dB, yani kabaca
30 kat menzil farkı. Ayrıca 446 MHz'in dalga boyu uzun olduğu için engelleri
dolaşır; 2.4 GHz soğurulur. Android'de verici gücünü artıran bir API yoktur.

Yazılımın yapabildiği iki şey var, ikisi de uygulamada:

1. **2.4 GHz'de kalmak.** Wi-Fi Direct grubu 5 GHz yerine 2.4 GHz'de kuruluyor
   (`setGroupOperatingBand`). Daha yavaş ama belirgin ölçüde daha uzak ve
   duvarı daha iyi geçiyor. Telsizde hız değil mesafe önemli.

2. **Köprüleme.** İki yola da bağlı olan bir cihaz, birinden gelen paketi
   diğerine aktarıyor. Yani aralarında şebekesi olan tek bir kişi varsa,
   internetsiz gruptaki herkes onun üzerinden dışarıyla konuşabiliyor.
   Aktarma tekilleştirmeden sonra yapıldığı için döngü oluşmuyor: her cihaz
   aynı paketi en fazla bir kez aktarır.

   Donanım eklemeden menzili gerçekten uzatmanın tek yolu bu — çünkü baz
   istasyonunun anteni ve gücü senin telefonunda olmayan şey.

Pratik olarak: grup sahibi telefonu yükseğe koy, araya duvar/metal sokma,
açık alanda ~50-100 m bekle. Kilometrelerce menzil isteniyorsa cevap
donanımdır — telefon o işi yapamaz.

## Relay sunucusu (internet için)

İki seçenek var; uygulama adresin şemasına bakıp kendisi seçiyor.

Röle adresi uygulamaya gömülü (`https://alkayazilim.com/relay.php`) ve
arayüzde görünmüyor: internet üzerinden konuşma, kullanıcı hiçbir şey
yapmadan çalışan bir özellik. Değiştirmek için `Prefs.DEFAULT_RELAY`
sabitini düzenleyip yeniden derlemek gerekiyor.

Kendi röleni kurmak istersen aşağıdaki iki yoldan biriyle.

### 1. Paylaşımlı hosting (cPanel / PHP) — en kolayı

`server/relay.php` dosyasını FTP ile ya da cPanel Dosya Yöneticisi'yle
sitenin herhangi bir klasörüne at.
Başka hiçbir şey gerekmiyor: Node yok, kurulum yok, sürekli çalışan süreç yok.

Uygulamadaki Relay alanına tam adresini yaz:

```
https://siteniz.com/telsiz/relay.php
```

Çalışıp çalışmadığını tarayıcıdan görebilirsin. Adresi açınca durum yazar;
sonuna `?test=1` eklersen gerçek bir test yapar — uygulamanın yaptığı gibi
bir paket gönderip karşıdan alır ve gecikmeyi ölçer:

```
https://siteniz.com/telsiz/relay.php?test=1
```

Bu test POST'un ve uzun bekleyen GET'in sunucunun Apache/PHP ayarlarından
geçtiğini doğrular — durum sayfasının söyleyemediği şey budur.

Nasıl çalışıyor: WebSocket olmadığı için ses ~200 ms'lik gruplar halinde POST
ediliyor, dinleyenler uzun bekleyen bir GET tutuyor (sunucu yeni ses gelene
kadar cevabı bekletiyor, boşuna sorgu yapılmıyor). Kanal dosyası 1 MB'ı
geçince sıfırlanıyor; canlı ses için geçmişin anlamı yok.

Ölçülen gecikme: yerel testte ortanca **139 ms**, en yüksek 224 ms. Gerçek
sunucuda buna gidiş-dönüş süresi ekleniyor, yani pratikte ~200-300 ms.

### 2. Node.js (VPS ya da Node destekleyen hosting)

`server/server.js` — WebSocket rölesi, gecikmesi daha düşük.

```bash
cd telsiz/server
npm install
npm start          # ws://<sunucu-ip>:8080
```

`Dockerfile` ve `render.yaml` de var. Adresi `Prefs.DEFAULT_RELAY`'e yaz;
`ws://`/`wss://` verilirse WebSocket, `http(s)://` verilirse PHP yolu kullanılır.

Her iki röle de sesi çözmez, saklamaz: paketleri aynı kanaldaki diğer
cihazlara olduğu gibi iletir.

## Teknik

- Hiç harici bağımlılık yok: androidx, okhttp vb. kullanılmıyor. WebSocket
  istemcisi (`WebSocketClient.kt`) elde yazıldı.
- `res/` klasörü yok; arayüz koddan kuruluyor.
- 16 kHz mono ses, **IMA ADPCM** ile 4:1 sıkıştırma → ~64 kbps.
- Kodlayıcı durumu kareler boyunca akar ama her paket kendi başlangıç
  durumunu başlığında taşır: paketler hâlâ birbirinden bağımsız çözülür
  (kaybolan paket sonrakileri bozmaz) ama kodlayıcı 40 ms'de bir sıfırdan
  başlamaz. Ölçülen SNR 20,1 → 34,1 dB.
- Mikrofonda tepe izleyen otomatik kazanç ve yumuşak sınırlama: kısık
  konuşma yükseltilir, tepe noktaları kırpılmak yerine 4:1 sıkıştırılır.
  Kazanç kodlamadan önce uygulanır — ADPCM'in hata payı sinyal seviyesine
  göreli olduğu için bu aynı zamanda kaliteyi de artırır.
- 40 ms'lik parçalar; gönderen başına 3 karelik (120 ms) jitter tamponu,
  aynı anda konuşanlar karıştırılır.
- LAN tarafında hem multicast (`239.255.42.99:47771`) hem subnet broadcast
  kullanılır — router'ların ve hotspot'ların hangisini geçirdiği değişiyor.
- Konuşurken hoparlör kapanır (yarı çift yönlü), böylece geri besleme olmaz.
- Telsiz bipi: konuşmanın başında tek kısa ton, sonunda inen iki ton.
  Ton ağdan geçmiyor, alıcıda üretiliyor (`Beep.kt`) — kodekten geçen bir
  bip cızırtılı çıkardı ve ilk/son paket kaybolursa hiç duyulmazdı. Ağda
  giden yalnızca "bip çal" işareti. Ayarlardan kapatılabilir.
- Ağ arayüzleri 2.5 sn'de bir taranır; değişince UDP soketi yeniden kurulup
  multicast üyelikleri tazelenir.
- Android 8.0 (API 26) ve üstü.
- Yazı tipleri APK'nın `assets/` klasöründen yükleniyor (res/ yok):
  Space Grotesk arayüz, JetBrains Mono ölçüm değerleri için. İkisi de
  SIL OFL; Latin+Türkçe karakterlere indirgenmiş hâlleri paketleniyor
  (dördü toplam 160 KB).

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
