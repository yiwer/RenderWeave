package cn.hbads.renderweave.inference;

import jakarta.servlet.http.HttpServletRequest;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Set;

/** Exact leaf-certificate fingerprint admission for already TLS-validated internal callers. */
final class ClientCertificateGate {
    private static final String CERTIFICATES_ATTRIBUTE =
            "jakarta.servlet.request.X509Certificate";
    private final Set<String> allowedSha256;

    ClientCertificateGate(Set<String> allowedSha256) {
        this.allowedSha256 = Set.copyOf(allowedSha256);
    }

    boolean permits(HttpServletRequest request) {
        var value = request.getAttribute(CERTIFICATES_ATTRIBUTE);
        if (!(value instanceof X509Certificate[] certificates) || certificates.length == 0) {
            return false;
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(certificates[0].getEncoded());
            return allowedSha256.contains(HexFormat.of().formatHex(digest));
        } catch (Exception failure) {
            return false;
        }
    }
}
