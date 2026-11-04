import com.android.apksig.ApkVerifier;

import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;

/**
 * Verificación de firma APK sin Android SDK, con la biblioteca oficial apksig
 * (incluida en apksig-x.y.z.jar o en bundletool-all-x.y.z.jar).
 *
 *   java -cp bundletool-all-1.18.1.jar tools/ApkSigCheck.java app.apk [otra.apk...]
 *
 * Comprueba la firma para Android 9 (API 28) y posteriores, como `apksigner verify --min-sdk-version 28`.
 */
public class ApkSigCheck {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Uso: java -cp apksig.jar ApkSigCheck.java archivo.apk [...]");
            System.exit(2);
        }
        boolean allOk = true;
        for (String path : args) {
            ApkVerifier.Result r = new ApkVerifier.Builder(new File(path)).setMinCheckedPlatformVersion(28).build().verify();
            System.out.println("APK: " + path);
            System.out.println("Verified: " + r.isVerified());
            System.out.println("Schemes: v1=" + r.isVerifiedUsingV1Scheme() + " v2=" + r.isVerifiedUsingV2Scheme()
                    + " v3=" + r.isVerifiedUsingV3Scheme());
            for (X509Certificate c : r.getSignerCertificates()) {
                System.out.println("Signer SHA-256: " + hex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded())));
                System.out.println("Signer subject: " + c.getSubjectX500Principal());
            }
            for (Object e : r.getErrors()) {
                System.out.println("Error: " + e);
            }
            allOk &= r.isVerified();
        }
        System.exit(allOk ? 0 : 1);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
