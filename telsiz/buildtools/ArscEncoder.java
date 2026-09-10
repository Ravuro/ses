import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kucuk bir resources.arsc uretir.
 *
 * aapt2 kullanilamadigi icin (Google sunuculari kapali) kaynak tablosunu
 * kendimiz yaziyoruz. Uygulamanin iki kaynagi var: uygulama ikonu ve
 * erisilebilirlik servisinin ayar dosyasi.
 *
 * Kullanim:
 *   ArscEncoder <paket> <cikti> <tip>:<ad>:<yol>[:<yogunluk>] ...
 *
 * Tipler goruldukleri sirayla 1'den numaralanir, girdiler de kendi tipleri
 * icinde 0'dan. Kaynak kimligi: 0x7f<tip><girdi>, ornegin ilk tipin ilk
 * girdisi 0x7f010000.
 */
public final class ArscEncoder {

    static final int RES_STRING_POOL_TYPE = 0x0001;
    static final int RES_TABLE_TYPE       = 0x0002;
    static final int RES_TABLE_PACKAGE    = 0x0200;
    static final int RES_TABLE_TYPE_TYPE  = 0x0201;
    static final int RES_TABLE_TYPE_SPEC  = 0x0202;

    static final int TYPE_STRING = 0x03;
    static final int PACKAGE_ID = 0x7f;
    static final int PACKAGE_HEADER_SIZE = 288;

    static final class Entry {
        final String key;      // "ic_launcher"
        final String path;     // "res/drawable-xxxhdpi/ic_launcher.png"
        Entry(String key, String path) { this.key = key; this.path = path; }
    }

    static final class Type {
        final String name;             // "drawable"
        final int density;             // 0 = varsayilan yapilandirma
        final List<Entry> entries = new ArrayList<>();
        Type(String name, int density) { this.name = name; this.density = density; }
    }

    static void u16(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }
    static void u32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >>> 24) & 0xFF);
    }
    static void write(ByteArrayOutputStream o, byte[] b) { o.write(b, 0, b.length); }

    /** AXML'dekiyle ayni bicim: UTF-16, siralanmamis. */
    static byte[] stringPool(List<String> strings) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = data.size();
            String s = strings.get(i);
            u16(data, s.length());
            write(data, s.getBytes(StandardCharsets.UTF_16LE));
            u16(data, 0);
        }
        while (data.size() % 4 != 0) data.write(0);

        int headerSize = 28;
        int stringsStart = headerSize + 4 * strings.size();

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, RES_STRING_POOL_TYPE);
        u16(o, headerSize);
        u32(o, stringsStart + data.size());
        u32(o, strings.size());
        u32(o, 0);              // style sayisi
        u32(o, 0);              // flags
        u32(o, stringsStart);
        u32(o, 0);              // style baslangici
        for (int off : offsets) u32(o, off);
        write(o, data.toByteArray());
        return o.toByteArray();
    }

    /** ResTable_config — yalnizca yogunluk alani dolu (0 ise varsayilan). */
    static byte[] config(int density) {
        byte[] c = new byte[56];
        c[0] = 56;                                  // size (uint32, kucuk-endian)
        c[14] = (byte) (density & 0xFF);
        c[15] = (byte) ((density >> 8) & 0xFF);
        return c;
    }

    static byte[] typeSpecChunk(int typeId, int entryCount) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, RES_TABLE_TYPE_SPEC);
        u16(o, 16);
        u32(o, 16 + 4 * entryCount);
        o.write(typeId); o.write(0); u16(o, 0);
        u32(o, entryCount);
        for (int i = 0; i < entryCount; i++) u32(o, 0);   // bayraklar
        return o.toByteArray();
    }

    static byte[] typeChunk(int typeId, Type t, Map<String, Integer> keyIndex,
                            Map<String, Integer> valueIndex) {
        byte[] cfg = config(t.density);
        int n = t.entries.size();
        int headerSize = 20 + cfg.length;
        int entriesStart = headerSize + 4 * n;

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] offsets = new int[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = body.size();
            Entry e = t.entries.get(i);
            u16(body, 8);                       // ResTable_entry boyutu
            u16(body, 0);                       // bayraklar (basit deger)
            u32(body, keyIndex.get(e.key));
            u16(body, 8);                       // Res_value boyutu
            body.write(0);                      // res0
            body.write(TYPE_STRING);
            u32(body, valueIndex.get(e.path));
        }

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, RES_TABLE_TYPE_TYPE);
        u16(o, headerSize);
        u32(o, entriesStart + body.size());
        o.write(typeId); o.write(0); u16(o, 0);
        u32(o, n);
        u32(o, entriesStart);
        write(o, cfg);
        for (int off : offsets) u32(o, off);
        write(o, body.toByteArray());
        return o.toByteArray();
    }

    static byte[] packageChunk(String packageName, List<Type> types,
                               Map<String, Integer> valueIndex) {
        List<String> typeNames = new ArrayList<>();
        List<String> keyNames = new ArrayList<>();
        Map<String, Integer> keyIndex = new LinkedHashMap<>();
        for (Type t : types) {
            typeNames.add(t.name);
            for (Entry e : t.entries) {
                if (!keyIndex.containsKey(e.key)) {
                    keyIndex.put(e.key, keyNames.size());
                    keyNames.add(e.key);
                }
            }
        }

        byte[] typeStrings = stringPool(typeNames);
        byte[] keyStrings = stringPool(keyNames);

        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        for (int i = 0; i < types.size(); i++) {
            Type t = types.get(i);
            int typeId = i + 1;
            write(tail, typeSpecChunk(typeId, t.entries.size()));
            write(tail, typeChunk(typeId, t, keyIndex, valueIndex));
        }

        int typeStringsOff = PACKAGE_HEADER_SIZE;
        int keyStringsOff = typeStringsOff + typeStrings.length;
        int size = keyStringsOff + keyStrings.length + tail.size();

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, RES_TABLE_PACKAGE);
        u16(o, PACKAGE_HEADER_SIZE);
        u32(o, size);
        u32(o, PACKAGE_ID);

        byte[] name = new byte[256];
        byte[] nb = packageName.getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(nb, 0, name, 0, Math.min(nb.length, 254));
        write(o, name);

        u32(o, typeStringsOff);
        u32(o, typeNames.size());     // lastPublicType
        u32(o, keyStringsOff);
        u32(o, keyNames.size());      // lastPublicKey
        u32(o, 0);                    // typeIdOffset

        write(o, typeStrings);
        write(o, keyStrings);
        write(o, tail.toByteArray());
        return o.toByteArray();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("kullanim: ArscEncoder <paket> <cikti> <tip>:<ad>:<yol>[:<yogunluk>] ...");
            System.exit(2);
        }
        String packageName = args[0];
        String out = args[1];

        Map<String, Type> byName = new LinkedHashMap<>();
        List<String> values = new ArrayList<>();
        Map<String, Integer> valueIndex = new LinkedHashMap<>();

        for (int i = 2; i < args.length; i++) {
            String[] p = args[i].split(":");
            if (p.length < 3) throw new IllegalArgumentException("bozuk kaynak: " + args[i]);
            int density = p.length > 3 ? Integer.parseInt(p[3]) : 0;
            Type t = byName.get(p[0]);
            if (t == null) {
                t = new Type(p[0], density);
                byName.put(p[0], t);
            }
            t.entries.add(new Entry(p[1], p[2]));
            if (!valueIndex.containsKey(p[2])) {
                valueIndex.put(p[2], values.size());
                values.add(p[2]);
            }
        }

        List<Type> types = new ArrayList<>(byName.values());
        byte[] valuePool = stringPool(values);
        byte[] pkg = packageChunk(packageName, types, valueIndex);

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, RES_TABLE_TYPE);
        u16(o, 12);
        u32(o, 12 + valuePool.length + pkg.length);
        u32(o, 1);              // paket sayisi
        write(o, valuePool);
        write(o, pkg);

        byte[] b = o.toByteArray();
        Files.write(Paths.get(out), b);

        System.out.printf("resources.arsc yazildi: %s (%d bayt)%n", out, b.length);
        for (int i = 0; i < types.size(); i++) {
            Type t = types.get(i);
            for (int j = 0; j < t.entries.size(); j++) {
                System.out.printf("  0x%08x  %s/%s%n",
                    (PACKAGE_ID << 24) | ((i + 1) << 16) | j, t.name, t.entries.get(j).key);
            }
        }
    }
}
