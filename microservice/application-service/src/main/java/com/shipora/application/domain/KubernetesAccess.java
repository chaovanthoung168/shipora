package com.shipora.application.domain;

import java.net.URI;
import java.util.Base64;
import static com.shipora.application.domain.ApplicationException.Kind.INVALID;

public record KubernetesAccess(String apiServer, String namespace, String token, String certificateAuthorityData) {
    public KubernetesAccess {
        try {
            URI uri = URI.create(apiServer);
            if (apiServer.length() > 2048 || !"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            apiServer = uri.resolve("/").toString().replaceFirst("/$", "");
        } catch (RuntimeException e) { throw invalid("apiServer must be an HTTPS origin, without credentials, path or query."); }
        if (namespace == null || namespace.length() > 63 || !namespace.matches("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?"))
            throw invalid("namespace must be a Kubernetes DNS label (1-63 characters).");
        if (token == null || token.length() > 16384 || !token.matches("[A-Za-z0-9._~+/-]+=*"))
            throw invalid("A non-empty Kubernetes bearer token without whitespace is required.");
        certificateAuthorityData = certificateAuthorityData == null ? "" : certificateAuthorityData;
        try {
            if (certificateAuthorityData.length() > 131072) throw new IllegalArgumentException();
            if (!certificateAuthorityData.isEmpty() && Base64.getDecoder().decode(certificateAuthorityData).length == 0)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw invalid("certificateAuthorityData must be base64-encoded PEM certificates (at most 128 KiB)."); }
    }
    private static ApplicationException invalid(String message) { return new ApplicationException(INVALID, message); }
    @Override public String toString() { return "KubernetesAccess[redacted]"; }
}
