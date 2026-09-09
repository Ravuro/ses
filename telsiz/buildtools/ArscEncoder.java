import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Tek girdilik bir resources.arsc uretir: uygulama ikonu.
 *
 * aapt2 kullanilamadigi icin (Google sunuculari kapali) kaynak tablosunu
 * kendimiz yaziyoruz. Uygulamanin baska hicbir kaynagi yok; burada sadece
 * @0x7f010000 -> res/drawable-xxxhdpi/ic_launcher.png eslemesi var.
 *
 * Yapi:
 *   RES_TABLE
 *     deger string havuzu           ("res/drawable-xxxhdpi/ic_launcher.png")
 *     RES_TABLE_PACKAGE (0x7f)
 *       tip string havuzu           ("drawable")
 *       anahtar string havuzu       ("ic_launcher")
 *       RES_TABLE_TYPE_SPEC
 *       RES_TABLE_TYPE              (yogunluk = xxxhdpi)
 */
public final class ArscEncoder {

    static final int RES_STRING_POOL_TYPE = 0x0001;
    static final int RES_TABLE_TYPE       = 0x0002;
    static final int RES_TABLE_PACKAGE    = 0x0200;
    static final int RES_TABLE_TYPE_TYPE  = 0x0201;
    static final int RES_TABLE_TYPE_SPEC  = 0x0202;

    static final int TYPE_STRING = 0x03;
    static final int DENSITY_XXXHIGH = 640;

    static final int PACKAGE_ID = 0x7f;
    static final int TYPE_ID = 1;              // "drawable"
    static final int PACKAGE_HEADER_SIZE = 288;

    static void u16(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }
    static void u32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >>> 24) & 0xFF);
    }
    static void write(ByteArrayOutputStream o, byte[] b) { o.write(b, 0, b.length); }

    /** AXML'dekiyle ayni bicim: UTF-16, siralanmamis. */
    static byte[] stringPool(String[] strings) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = data.size();
            String s = strings[i];
            u16(data, s.length());
            write(data, s.getBytes(StandardCharsets.UTF_16LE));
            u16(data, 0);
        }
        while (data.size() % 4 != 0) data.write(0);

        int headerSize = 28;
        int stringsStart = headerSize + 4 * strings.length;

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, RES_STRING_POOL_TYPE);
        u16(o, headerSize);
        u32(o, stringsStart + data.size());
        u32(o, strings.length);
        u32(o, 0);              // style sayisi
        u32(o, 0);              // flags
        u32(o, stringsStart);
        u32(o, 0);              // style baslangici
        for (int off : offsets) u32(o, off);
        write(o, data.toByteArray());
        return o.toByteArray();
    }

    /** ResTable_config — yalnizca yogunluk alani dolu. */
    static byte[] config() {
        byte[] c = new byte[56];
        c[0] = 56;                                   // size (uint32, kucuk-endian)
        c[14] = (byte) (DENSITY_XXXHIGH & 0xFF);     // density
        c[15] = (byte) ((DENSITY_XXXHIGH >> 8) & 0xFF);
        return c;
    }

    static byte[] typeSpecChunk() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, RES_TABLE_TYPE_SPEC);
        u16(o, 16);
        u32(o, 16 + 4);      // tek girdi
        o.write(TYPE_ID); o.write(0); u16(o, 0);
        u32(o, 1);           // girdi sayisi
        u32(o, 0);           // bayraklar
        return o.toByteArray();
    }

    static byte[] typeChunk(int valueStringIndex) {
        byte[] cfg = config();
        int headerSize = 20 + cfg.length;
        int entriesStart = headerSize + 4;   // ofset dizisi (1 girdi)

        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        u16(entry, 8);          // ResTable_entry boyutu
        u16(entry, 0);          // bayraklar (basit deger)
        u32(entry, 0);          // anahtar string havuzu indeksi -> "ic_launcher"
        u16(entry, 8);          // Res_value boyutu
        entry.write(0);         // res0
        entry.write(TYPE_STRING);
        u32(entry, valueStringIndex);
        byte[] e = entry.toByteArray();

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, RES_TABLE_TYPE_TYPE);
        u16(o, headerSize);
        u32(o, entriesStart + e.length);
        o.write(TYPE_ID); o.write(0); u16(o, 0);
        u32(o, 1);              // girdi sayisi
        u32(o, entriesStart);
        write(o, cfg);
        u32(o, 0);              // girdi ofseti
        write(o, e);
        return o.toByteArray();
    }

    static byte[] packageChunk(String packageName) {
        byte[] typeStrings = stringPool(new String[]{"drawable"});
        byte[] keyStrings = stringPool(new String[]{"ic_launcher"});
        byte[] spec = typeSpecChunk();
        byte[] type = typeChunk(0);

        int typeStringsOff = PACKAGE_HEADER_SIZE;
        int keyStringsOff = typeStringsOff + typeStrings.length;
        int size = keyStringsOff + keyStrings.length + spec.length + type.length;

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, RES_TABLE_PACKAGE);
        u16(o, PACKAGE_HEADER_SIZE);
        u32(o, size);
        u32(o, PACKAGE_ID);

        // name[128], UTF-16, sifir dolgulu
        byte[] name = new byte[256];
        byte[] n = packageName.getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(n, 0, name, 0, Math.min(n.length, 254));
        write(o, name);

        u32(o, typeStringsOff);
        u32(o, 1);              // lastPublicType
        u32(o, keyStringsOff);
        u32(o, 1);              // lastPublicKey
        u32(o, 0);              // typeIdOffset

        write(o, typeStrings);
        write(o, keyStrings);
        write(o, spec);
        write(o, type);
        return o.toByteArray();
    }

    public static void main(String[] args) throws IOException {
        String packageName = args[0];
        String iconPath = args[1];      // ornek: res/drawable-xxxhdpi/ic_launcher.png
        String out = args[2];

        byte[] valuePool = stringPool(new String[]{iconPath});
        byte[] pkg = packageChunk(packageName);

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, RES_TABLE_TYPE);
        u16(o, 12);
        u32(o, 12 + valuePool.length + pkg.length);
        u32(o, 1);              // paket sayisi
        write(o, valuePool);
        write(o, pkg);

        byte[] b = o.toByteArray();
        Files.write(Paths.get(out), b);
        System.out.printf("resources.arsc yazildi: %s (%d bayt), ikon kaynak kimligi 0x%08x%n",
            out, b.length, (PACKAGE_ID << 24) | (TYPE_ID << 16));
    }
}
