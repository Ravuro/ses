import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Metin AndroidManifest.xml -> ikili AXML.
 *
 * aapt2 kullanamadigimiz icin (Google sunuculari kapali) manifesti kendimiz
 * kodluyoruz. Attribute kaynak ID'leri tahmin edilmiyor: android.jar icindeki
 * android.R$attr / R$style sabitlerinden reflection ile okunuyor.
 */
public final class AxmlEncoder {

    // Chunk tipleri
    static final int RES_XML_TYPE            = 0x0003;
    static final int RES_STRING_POOL_TYPE    = 0x0001;
    static final int RES_XML_RESOURCE_MAP    = 0x0180;
    static final int RES_XML_START_NAMESPACE = 0x0100;
    static final int RES_XML_END_NAMESPACE   = 0x0101;
    static final int RES_XML_START_ELEMENT   = 0x0102;
    static final int RES_XML_END_ELEMENT     = 0x0103;

    // Res_value tipleri
    static final int TYPE_REFERENCE   = 0x01;
    static final int TYPE_STRING      = 0x03;
    static final int TYPE_INT_DEC     = 0x10;
    static final int TYPE_INT_HEX     = 0x11;
    static final int TYPE_INT_BOOLEAN = 0x12;

    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    // ---- string havuzu ----
    final List<String> pool = new ArrayList<>();
    final Map<String, Integer> poolIndex = new HashMap<>();
    /** Havuzun basindaki attribute isimlerine karsilik gelen kaynak ID'leri. */
    final List<Integer> resourceMap = new ArrayList<>();

    int intern(String s) {
        Integer i = poolIndex.get(s);
        if (i != null) return i;
        int idx = pool.size();
        pool.add(s);
        poolIndex.put(s, idx);
        return idx;
    }

    // ---- attribute ID cozumleme ----
    static int androidResId(String type, String name) {
        try {
            Class<?> c = Class.forName("android.R$" + type);
            return c.getField(name.replace('.', '_')).getInt(null);
        } catch (Exception e) {
            return 0;
        }
    }

    static final class Attr {
        String ns, name, raw;
        int resId, dataType, data;
    }

    static final class Elem {
        String name;
        List<Attr> attrs = new ArrayList<>();
        List<Elem> children = new ArrayList<>();
    }

    // ---- XML -> ic model ----
    Elem convert(Element e) {
        Elem out = new Elem();
        out.name = e.getTagName();
        NamedNodeMap m = e.getAttributes();
        for (int i = 0; i < m.getLength(); i++) {
            Node n = m.item(i);
            String qn = n.getNodeName();
            if (qn.equals("xmlns:android")) continue;
            Attr a = new Attr();
            if (qn.startsWith("android:")) {
                a.ns = ANDROID_NS;
                a.name = qn.substring("android:".length());
                a.resId = androidResId("attr", a.name);
                if (a.resId == 0) {
                    throw new RuntimeException("android:" + a.name + " icin kaynak ID bulunamadi");
                }
            } else {
                a.ns = null;
                a.name = qn;
                a.resId = 0;
            }
            setValue(a, n.getNodeValue());
            out.attrs.add(a);
        }
        // aapt attribute'lari kaynak ID'sine gore siralar; obtainStyledAttributes
        // birlestirmeli tarama yaptigi icin bu sira sart.
        out.attrs.sort((x, y) -> Integer.compareUnsigned(x.resId, y.resId));

        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element) out.children.add(convert((Element) kids.item(i)));
        }
        return out;
    }

    void setValue(Attr a, String v) {
        if (v.startsWith("@android:")) {
            String body = v.substring("@android:".length());
            int slash = body.indexOf('/');
            int id = androidResId(body.substring(0, slash), body.substring(slash + 1));
            if (id == 0) throw new RuntimeException("cozulemeyen referans: " + v);
            a.dataType = TYPE_REFERENCE;
            a.data = id;
            a.raw = null;
        } else if (v.startsWith("@0x")) {
            // Kendi paketimizin kaynagina ham referans: res/ klasoru ve aapt2
            // olmadigi icin "@drawable/..." adiyla cozecek bir tablo yok.
            a.dataType = TYPE_REFERENCE;
            a.data = (int) Long.parseLong(v.substring(3), 16);
            a.raw = null;
        } else if (v.equals("true") || v.equals("false")) {
            a.dataType = TYPE_INT_BOOLEAN;
            a.data = v.equals("true") ? 0xFFFFFFFF : 0;
            a.raw = null;
        } else if (v.matches("0x[0-9a-fA-F]+")) {
            a.dataType = TYPE_INT_HEX;
            a.data = (int) Long.parseLong(v.substring(2), 16);
            a.raw = null;
        } else if (v.matches("-?\\d+")) {
            a.dataType = TYPE_INT_DEC;
            a.data = Integer.parseInt(v);
            a.raw = null;
        } else {
            a.dataType = TYPE_STRING;
            a.raw = v;
        }
    }

    /** Attribute isimlerini havuzun basina, kaynak ID sirasiyla yerlestirir. */
    void buildAttrNamePool(Elem root, TreeMap<Integer, String> byId, Set<String> noId) {
        for (Attr a : root.attrs) {
            if (a.resId != 0) byId.put(a.resId, a.name);
            else noId.add(a.name);
        }
        for (Elem c : root.children) buildAttrNamePool(c, byId, noId);
    }

    void collectStrings(Elem e) {
        intern(e.name);
        for (Attr a : e.attrs) {
            intern(a.name);
            if (a.raw != null) intern(a.raw);
        }
        for (Elem c : e.children) collectStrings(c);
    }

    // ---- ikili yazim ----
    static void putU16(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }
    static void putU32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >>> 24) & 0xFF);
    }

    byte[] stringPoolChunk() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            offsets[i] = data.size();
            String s = pool.get(i);
            putU16(data, s.length());
            byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
            data.write(utf16, 0, utf16.length);
            putU16(data, 0); // sonlandirici
        }
        while (data.size() % 4 != 0) data.write(0);

        int headerSize = 28;
        int stringsStart = headerSize + 4 * pool.size();
        int size = stringsStart + data.size();

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        putU16(o, RES_STRING_POOL_TYPE);
        putU16(o, headerSize);
        putU32(o, size);
        putU32(o, pool.size());
        putU32(o, 0);            // style sayisi
        putU32(o, 0);            // flags: UTF-16, sirasiz
        putU32(o, stringsStart);
        putU32(o, 0);            // style baslangici
        for (int off : offsets) putU32(o, off);
        byte[] d = data.toByteArray();
        o.write(d, 0, d.length);
        return o.toByteArray();
    }

    byte[] resourceMapChunk() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        putU16(o, RES_XML_RESOURCE_MAP);
        putU16(o, 8);
        putU32(o, 8 + 4 * resourceMap.size());
        for (int id : resourceMap) putU32(o, id);
        return o.toByteArray();
    }

    byte[] namespaceChunk(int type, int prefixIdx, int uriIdx) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        putU16(o, type);
        putU16(o, 16);
        putU32(o, 24);
        putU32(o, 1);   // satir
        putU32(o, -1);  // yorum
        putU32(o, prefixIdx);
        putU32(o, uriIdx);
        return o.toByteArray();
    }

    void writeElem(ByteArrayOutputStream out, Elem e, int nsUriIdx) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int size = 16 + 20 + 20 * e.attrs.size();
        putU16(o, RES_XML_START_ELEMENT);
        putU16(o, 16);
        putU32(o, size);
        putU32(o, 1);
        putU32(o, -1);
        putU32(o, -1);                 // eleman ad alani
        putU32(o, intern(e.name));
        putU16(o, 20);                 // attributeStart
        putU16(o, 20);                 // attributeSize
        putU16(o, e.attrs.size());
        putU16(o, 0);                  // id index
        putU16(o, 0);                  // class index
        putU16(o, 0);                  // style index
        for (Attr a : e.attrs) {
            putU32(o, a.ns == null ? -1 : nsUriIdx);
            putU32(o, intern(a.name));
            putU32(o, a.raw == null ? -1 : intern(a.raw));
            putU16(o, 8);              // Res_value boyutu
            o.write(0);                // res0
            o.write(a.dataType);
            putU32(o, a.dataType == TYPE_STRING ? intern(a.raw) : a.data);
        }
        byte[] b = o.toByteArray();
        out.write(b, 0, b.length);

        for (Elem c : e.children) writeElem(out, c, nsUriIdx);

        ByteArrayOutputStream end = new ByteArrayOutputStream();
        putU16(end, RES_XML_END_ELEMENT);
        putU16(end, 16);
        putU32(end, 24);
        putU32(end, 1);
        putU32(end, -1);
        putU32(end, -1);
        putU32(end, intern(e.name));
        byte[] eb = end.toByteArray();
        out.write(eb, 0, eb.length);
    }

    byte[] encode(File xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        Document doc = f.newDocumentBuilder().parse(xml);
        doc.getDocumentElement().normalize();
        Elem root = convert(doc.getDocumentElement());

        // 1) Attribute isimleri havuzun basinda, kaynak ID sirasiyla olmali:
        //    kaynak haritasi indeks indeks eslesiyor.
        TreeMap<Integer, String> byId = new TreeMap<>(Integer::compareUnsigned);
        Set<String> noId = new LinkedHashSet<>();
        buildAttrNamePool(root, byId, noId);
        for (String n : noId) { intern(n); resourceMap.add(0); }
        for (Map.Entry<Integer, String> en : byId.entrySet()) {
            intern(en.getValue());
            resourceMap.add(en.getKey());
        }
        // 2) Geri kalan stringler
        int prefixIdx = intern("android");
        int uriIdx = intern(ANDROID_NS);
        collectStrings(root);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] ns = namespaceChunk(RES_XML_START_NAMESPACE, prefixIdx, uriIdx);
        body.write(ns, 0, ns.length);
        writeElem(body, root, uriIdx);
        byte[] nse = namespaceChunk(RES_XML_END_NAMESPACE, prefixIdx, uriIdx);
        body.write(nse, 0, nse.length);

        byte[] sp = stringPoolChunk();
        byte[] rm = resourceMapChunk();
        byte[] bd = body.toByteArray();

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        putU16(o, RES_XML_TYPE);
        putU16(o, 8);
        putU32(o, 8 + sp.length + rm.length + bd.length);
        o.write(sp, 0, sp.length);
        o.write(rm, 0, rm.length);
        o.write(bd, 0, bd.length);
        return o.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        byte[] out = new AxmlEncoder().encode(new File(args[0]));
        Files.write(Paths.get(args[1]), out);
        System.out.println("AXML yazildi: " + args[1] + " (" + out.length + " bayt)");
    }
}
