package com.shipora.project.adapter.out.ssh;

import com.shipora.project.configuration.SshConnectionProperties.Target;

import java.util.Arrays;

final class ResolvedSshCredential implements AutoCloseable {
    private final Target target;
    private final char[] passphrase;

    ResolvedSshCredential(Target target, char[] passphrase) {
        this.target = target;
        this.passphrase = passphrase;
    }

    Target target() {
        return target;
    }

    char[] passphrase() {
        return passphrase;
    }

    @Override
    public void close() {
        Arrays.fill(passphrase, '\0');
    }
}
