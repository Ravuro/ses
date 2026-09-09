#!/bin/bash
#
# Android SDK olmadan APK derler.
#
# Neden: bu projenin derlendigi ortamda Google'in sunuculari (dl.google.com,
# maven.google.com) ag politikasiyla kapali. Yani ne Android SDK, ne aapt2,
# ne d8, ne de androidx indirilebiliyor. Onlarin yerine:
#
#   android.jar   -> API stub'lari (derleme icin; APK'ya girmez)
#   kotlinc       -> Kotlin derleyicisi
#   dx            -> DEX ureteci (Maven Central'daki repackage)
#   AxmlEncoder   -> manifesti ikili AXML'e ceviren kendi kodlayicimiz
#   apksig        -> v2 imzalama
#
# Uygulamanin hicbir harici bagimliligi yok (androidx/okhttp kullanilmiyor)
# ve res/ klasoru yok; arayuz koddan kuruluyor. Bu sayede aapt2 gerekmiyor.
#
# Normalde Android SDK'si olan bir makinede: ./gradlew assembleRelease
#
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="${TELSIZ_WORK:-$ROOT/.nosdk}"
SRC="$ROOT/app/src/main/java/com/ravuro/telsiz"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"

PKG=com.ravuro.telsiz
MIN_SDK=26
TARGET_SDK=28
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"

KOTLIN_VERSION=2.0.21
MC=https://repo1.maven.org/maven2

mkdir -p "$WORK/tools"
cd "$WORK"

fetch() { # url hedef
    [ -f "$2" ] && return 0
    echo "  indiriliyor: $(basename "$2")"
    curl -fsSL --retry 3 -o "$2" "$1"
}

echo "[1/7] Araclar hazirlaniyor"
fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip" tools/kotlinc.zip
[ -d kotlinc ] || unzip -q tools/kotlinc.zip -d .
fetch "https://raw.githubusercontent.com/Sable/android-platforms/master/android-35/android.jar" android.jar
fetch "$MC/com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar" tools/dx.jar
fetch "$MC/com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar" tools/apksig.jar

# dx cok surumlu jar artiklarini sindiremiyor
if [ ! -f stdlib.jar ]; then
    cp kotlinc/lib/kotlin-stdlib.jar stdlib.jar
    zip -q -d stdlib.jar 'META-INF/versions/*' 'module-info.class' 2>/dev/null || true
fi

mkdir -p tools/classes
javac -nowarn -cp tools/apksig.jar -d tools/classes \
    "$ROOT/buildtools/AxmlEncoder.java" "$ROOT/buildtools/Sign.java" \
    "$ROOT/buildtools/ArscEncoder.java" "$ROOT/buildtools/MakeIcon.java"

echo "[2/7] Kotlin derleniyor"
rm -rf classes stage
# dx invokedynamic'i okuyamiyor: lambdalar sinif olarak uretilsin.
kotlinc/bin/kotlinc -nowarn -cp android.jar -jvm-target 1.8 \
    -Xlambdas=class -Xsam-conversions=class -d classes "$SRC"/*.kt

echo "[3/7] DEX uretiliyor"
mkdir -p stage
java -Xmx2g -cp tools/dx.jar com.android.dx.command.Main --dex \
    --min-sdk-version=$MIN_SDK --output=stage/classes.dex classes stdlib.jar

echo "[4/7] Ikon ve kaynak tablosu uretiliyor"
# Uygulamanin tek kaynagi ikon. aapt2 olmadigi icin PNG'yi biz ciziyor,
# resources.arsc'yi biz yaziyoruz.
mkdir -p stage/res/drawable-xxxhdpi
java -cp tools/classes MakeIcon 192 stage/res/drawable-xxxhdpi/ic_launcher.png
java -cp tools/classes ArscEncoder "$PKG" res/drawable-xxxhdpi/ic_launcher.png stage/resources.arsc

echo "[5/7] Manifest ikili formata cevriliyor"
python3 - "$MANIFEST" stage/manifest-src.xml "$PKG" "$MIN_SDK" "$TARGET_SDK" \
         "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import sys
src, dst, pkg, mn, tg, vc, vn = sys.argv[1:8]
s = open(src, encoding='utf-8').read()
# Gradle bunlari build dosyasindan enjekte ediyor; elde derlerken biz koyuyoruz.
s = s.replace(
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    package="%s"\n    android:versionCode="%s"\n    android:versionName="%s">\n'
    '    <uses-sdk android:minSdkVersion="%s" android:targetSdkVersion="%s" />'
    % (pkg, vc, vn, mn, tg))
# Ikon kaynagi ArscEncoder'in urettigi tabloda 0x7f010000'da duruyor.
s = s.replace('<application\n', '<application\n        android:icon="@0x7f010000"\n', 1)
assert 'android:icon' in s, 'ikon niteligi eklenemedi'
open(dst, 'w', encoding='utf-8').write(s)
PY
java -cp "tools/classes:android.jar" AxmlEncoder stage/manifest-src.xml stage/AndroidManifest.xml
rm -f stage/manifest-src.xml

echo "[6/7] Paketleniyor"
rm -f unsigned.apk telsiz.apk
(cd stage && zip -q -X -r ../unsigned.apk AndroidManifest.xml resources.arsc classes.dex res)

echo "[7/7] Imzalaniyor"
# Anahtar depoya girmiyor. Yeniden uretilirse imza degisir; o durumda
# telefondaki eski surumu once kaldirmak gerekir.
if [ ! -f telsiz.p12 ]; then
    keytool -genkeypair -alias telsiz -keyalg RSA -keysize 2048 -validity 10000 \
        -storetype PKCS12 -keystore telsiz.p12 -storepass telsiz123 -keypass telsiz123 \
        -dname "CN=Telsiz, O=Ravuro, C=TR"
fi
# apksig 2.3.0 (2017) JDK'nin ic siniflarini kullaniyor; JDK 9+ icin acmak gerek.
# v1 (JAR) imzasi modern JDK'de calismiyor ama minSdk 26 icin v2 yeterli.
java --add-exports java.base/sun.security.x509=ALL-UNNAMED \
     --add-exports java.base/sun.security.pkcs=ALL-UNNAMED \
     -cp "tools/classes:tools/apksig.jar" Sign \
     unsigned.apk telsiz.apk telsiz.p12 telsiz123 $MIN_SDK

cp telsiz.apk "$ROOT/../telsiz.apk"
echo
echo "Hazir: $ROOT/../telsiz.apk"
ls -lh telsiz.apk
