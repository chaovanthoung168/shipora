package com.shipora.application.application.port.out;

import com.shipora.application.domain.KubernetesAccess;

public interface KubernetesProbe {
    Result check(KubernetesAccess access);
    record Result(boolean allowed, Code code) {}
    enum Code { OK, AUTHENTICATION_FAILED, ACCESS_DENIED, NAMESPACE_NOT_FOUND, INVALID_CERTIFICATE, TLS_FAILED, UNREACHABLE, INVALID_RESPONSE }
}
