# Windows'ta çalışmak

Bu klasör uygulamanın tamamı: kaynak kod, iki ayrı derleme yolu, PHP rölesi
ve kurulmaya hazır APK.

## Sadece kurmak istiyorsan

`telsiz.apk` dosyasını telefona kopyala ve kur. Başka bir şey gerekmiyor.
Telefon "bilinmeyen kaynak" uyarısı verirse kurulum iznini ver.

## Kod üzerinde çalışmak / kendin derlemek

### Yol 1 — Android Studio (Windows'ta doğal yol)

1. [Android Studio](https://developer.android.com/studio) kur.
2. **Open** deyip bu klasörü seç (`build.gradle.kts` olanı).
3. Studio, Android SDK ve Gradle'ı kendisi indirir. İlk açılış birkaç dakika sürer.
4. **Run** (yeşil üçgen) ya da terminalde:

   ```
   gradlew.bat assembleDebug
   ```

   Çıktı: `app\build\outputs\apk\debug\app-debug.apk`

Projenin **hiçbir dış bağımlılığı yok** — androidx, okhttp, Firebase yok.
Arayüz koddan kuruluyor, `res/` klasörü neredeyse boş. Bu yüzden Gradle
tarafı hızlı ve sorunsuz kurulur.

### Yol 2 — SDK olmadan (`build-nosdk.sh`)

Bu betik uygulamanın geliştirildiği ortamda kullanılıyor, çünkü orada
Google'ın sunucuları ağ politikasıyla kapalı: ne Android SDK, ne `aapt2`,
ne `d8` indirilebiliyor. Onların yerine `kotlinc`, `dx` ve elde yazılmış
bir AXML/`resources.arsc` kodlayıcısı kullanılıyor.

Windows'ta çalıştırmak istersen Git Bash ya da WSL gerekir:

```
bash build-nosdk.sh
```

Gereken: JDK 17+, `curl`, `unzip`, `zip`. İlk çalıştırmada araçları
indirir (~200 MB), sonrakiler hızlıdır.

Kısacası: **Windows'ta Yol 1'i kullan.** Yol 2 sadece SDK'ya erişilemeyen
ortamlar için var.

## Sürüm numarası

`app/build.gradle.kts` içindeki `versionCode` ile `build-nosdk.sh`
içindeki `VERSION_CODE` aynı tutulmalı. Telefonda kurulu olandan düşük
numaralı bir APK kurulmuyor, "downgrade" hatası veriyor.

## Klasörde ne var

| Yol | Ne |
|---|---|
| `app/src/main/java/com/ravuro/telsiz/` | Uygulamanın tamamı (Kotlin) |
| `app/src/main/AndroidManifest.xml` | İzinler, servisler, nabız alıcısı |
| `app/src/main/assets/fonts/` | Gömülü yazı tipleri |
| `buildtools/` | SDK'sız derleme için elde yazılmış kodlayıcılar |
| `server/relay.php` | İnternet yolu için PHP rölesi (cPanel'e atılıyor) |
| `README.md` | Nasıl çalıştığının tam anlatımı |
| `telsiz.apk` | Kurulmaya hazır sürüm |

## Röle sunucusu

`server/relay.php` şu an `alkayazilim.com/relay.php` adresinde çalışıyor ve
uygulamaya gömülü. Değiştirmek istersen `Prefs.kt` içindeki
`DEFAULT_RELAY` sabitini düzenle.

`https://alkayazilim.com/relay.php?test=1` adresi röleyi kendi kendine test
eden bir sayfa açıyor.
