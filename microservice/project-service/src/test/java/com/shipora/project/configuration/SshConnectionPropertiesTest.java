package com.shipora.project.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SshConnectionPropertiesTest {
    @Test
    void bindsDefaultTimeoutsAndConfiguredTarget() {
        var source = new MapConfigurationPropertySource(Map.of(
                "shipora.ssh.targets[0].organization-id", "11111111-1111-1111-1111-111111111111",
                "shipora.ssh.targets[0].credential-secret-ref", "test/key",
                "shipora.ssh.targets[0].host", "127.0.0.1",
                "shipora.ssh.targets[0].username", "root",
                "shipora.ssh.targets[0].host-key-fingerprint",
                "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(new byte[32]),
                "shipora.ssh.targets[0].private-key", "test-key"));
        var properties = new Binder(source)
                .bind("shipora.ssh", Bindable.of(SshConnectionProperties.class)).get();
        assertEquals(Duration.ofSeconds(5), properties.connectTimeout());
        assertEquals(Duration.ofSeconds(15), properties.checkTimeout());
        assertEquals(22, properties.targets().getFirst().port());
        assertEquals("ssh-ed25519", properties.targets().getFirst().hostKeyAlgorithm());
    }

    @Test
    void rejectsUnboundedOrZeroTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> new SshConnectionProperties(
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2), 1, null));
        assertThrows(IllegalArgumentException.class, () -> new SshConnectionProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 100, null));
    }
}
