import app.webgen.core.ApkBuilder;
import app.webgen.core.SelfSignedCert;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * JVM test harness for the generator core (NOT shipped in the app).
 * Uses the exact same app.webgen.core classes that are dexed into the
 * generator, plus the apksig classes from the SDK apksigner.jar.
 *
 * args: <template.apk> <icon.png> <keyDir> <outOn.apk> <outOff.apk>
 * Fixed sample: URL https://ja.wikipedia.org, name "Wikiサンプル",
 * package app.webgen.sample.wiki, templateVersion 6.
 *   outOn:  every capability toggle ON  (manifest keeps real permissions)
 *   outOff: every capability toggle OFF (permission names neutralized)
 */
public class TestMain {
    public static void main(String[] args) throws Exception {
        File template = new File(args[0]);
        File icon = new File(args[1]);
        File keyDir = new File(args[2]);
        File outOn = new File(args[3]);
        File outOff = new File(args[4]);

        SelfSignedCert.KeyMaterial keys = SelfSignedCert.loadOrCreate(keyDir);
        byte[] certDer = keys.certificate.getEncoded();
        String sha256 = hex(MessageDigest.getInstance("SHA-256").digest(certDer));
        System.out.println("signer cert subject: " + keys.certificate.getSubjectX500Principal());
        System.out.println("signer cert SHA-256: " + sha256);
        System.out.println("signer cert serial: " + keys.certificate.getSerialNumber());
        System.out.println("signer cert notAfter: " + keys.certificate.getNotAfter());

        Map<String, byte[]> icons = new HashMap<>();
        icons.put("*", Files.readAllBytes(icon.toPath()));

        ApkBuilder.BuildOptions on = new ApkBuilder.BuildOptions();
        on.microphone = true;
        on.camera = true;
        on.location = true;
        on.notifications = true;
        on.templateVersion = 6;
        ApkBuilder.Result rOn = ApkBuilder.buildAndSign(
                template, outOn,
                "https://ja.wikipedia.org", "Wikiサンプル", "app.webgen.sample.wiki",
                icons, keys, on);
        System.out.println("ON  patched: config=" + rOn.configReplaced
                + " manifest=" + rOn.manifestPatched + " icons=" + rOn.iconsReplaced
                + " permissionsDisabled=" + rOn.permissionsDisabled);
        System.out.println("OK (ON)  -> " + outOn + " (" + outOn.length() + " bytes)");

        ApkBuilder.BuildOptions off = new ApkBuilder.BuildOptions();
        off.microphone = false;
        off.camera = false;
        off.location = false;
        off.notifications = false;
        off.templateVersion = 6;
        ApkBuilder.Result rOff = ApkBuilder.buildAndSign(
                template, outOff,
                "https://ja.wikipedia.org", "Wikiサンプル", "app.webgen.sample.wiki",
                icons, keys, off);
        System.out.println("OFF patched: config=" + rOff.configReplaced
                + " manifest=" + rOff.manifestPatched + " icons=" + rOff.iconsReplaced
                + " permissionsDisabled=" + rOff.permissionsDisabled);
        System.out.println("OK (OFF) -> " + outOff + " (" + outOff.length() + " bytes)");

        // second load: proves loadOrCreate reuses the same persisted key
        SelfSignedCert.KeyMaterial keys2 = SelfSignedCert.loadOrCreate(keyDir);
        String sha256b = hex(MessageDigest.getInstance("SHA-256")
                .digest(keys2.certificate.getEncoded()));
        System.out.println("key reuse check: " + sha256.equals(sha256b));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
