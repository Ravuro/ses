# Telsiz arayüz tasarımı

Uygulamanın ekranlarının tasarım kanvası. Yayındaki hali:
https://claude.ai/code/artifact/b9899cdf-bf31-4125-89f2-d6be44e7ef3a

| Dosya | Ekran |
|---|---|
| `Main.dc.html` | Konuşma ekranı — bas-konuşun üç hali `durum` düğmesinden görülür |
| `Kurulum.dc.html` | Ad, kanal, röle, ses tuşu |
| `TelsizAgi.dc.html` | Wi-Fi Direct grubu açıkken: ağ adı ve parola |
| `AlternatifB.dc.html` | Alternatif yön (eskiz): tek dev bas-konuş hedefi |
| `canvas.json` | Yerleşim ve notlar |

## Yön

Saha cihazı. Telsiz dışarıda, elde, çoğu zaman ekrana bakılmadan
kullanılıyor; yumuşak bir dil yerine ölçüm aleti dili seçildi.

- Koyu zemin (#0A0D12), tek sinyal rengi, telemetri için monospace
- Space Grotesk (arayüz) + JetBrains Mono (veri)
- Yeşil: sen konuşuyorsun · camgöbeği: karşı taraf konuşuyor · kehribar: uyarı

Uygulamadaki hâline göre üç yapısal değişiklik: kanal ekranın kimliği
oldu, durum bilgisi ölçüm kutularına ayrıldı, telsiz ağı ekranı yüksek
sesle okunmak üzere düzenlendi (ağ adı ve parola sayfanın en büyük yazısı).

## Değiştirmek

Kaynak bu klasördeki dosyalar; yayındaki sayfa onlardan üretiliyor.
Dosyaları düzenledikten sonra kanvas yeniden seed edilip aynı adrese
yayımlanır (`/design` becerisi). Seed edilmiş çıktı depoya girmiyor —
2.4 MB'lik editör yükü taşıyor.
