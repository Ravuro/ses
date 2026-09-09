import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Imzasiz APK'yi v1+v2 ile imzalar ve sonucu dogrular. */
public class Sign {
    public static void main(String[] args) throws Exception {
        File in = new File(args[0]);
        File out = new File(args[1]);
        String storePath = args[2];
        char[] pw = args[3].toCharArray();
        int minSdk = Integer.parseInt(args[4]);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream f = new FileInputStream(storePath)) { ks.load(f, pw); }
        String alias = ks.aliases().nextElement();
        PrivateKey key = (PrivateKey) ks.getKey(alias, pw);
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : ks.getCertificateChain(alias)) certs.add((X509Certificate) c);

        ApkSigner.SignerConfig cfg =
            new ApkSigner.SignerConfig.Builder("telsiz", key, certs).build();

        new ApkSigner.Builder(Collections.singletonList(cfg))
            .setInputApk(in)
            .setOutputApk(out)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .build()
            .sign();

        ApkVerifier.Result r = new ApkVerifier.Builder(out).build().verify();
        System.out.println("imza dogrulandi : " + r.isVerified()
            + "  (v1=" + r.isVerifiedUsingV1Scheme()
            + ", v2=" + r.isVerifiedUsingV2Scheme() + ")");
        for (Object e : r.getErrors()) System.out.println("  HATA: " + e);
        for (Object w : r.getWarnings()) System.out.println("  uyari: " + w);
        if (!r.isVerified()) System.exit(1);
    }
}
