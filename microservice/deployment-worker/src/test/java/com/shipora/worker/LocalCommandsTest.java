package com.shipora.worker;

import com.shipora.worker.adapter.out.process.LocalCommands;
import com.shipora.worker.domain.ExecutionFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalCommandsTest {
    @TempDir Path root;
    private List<String> command(String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"), Child.class.getName(), mode);
    }
    @Test void timeoutTerminatesAProcessWithoutWaitingForItsOutputReaders() {
        var failure = assertTimeoutPreemptively(Duration.ofSeconds(8), () -> assertThrows(ExecutionFailure.class,
                () -> new LocalCommands().run(command("wait"), root, Map.of(), Duration.ofMillis(300), () -> {}, line -> {}, true)));
        assertEquals("WORKER_FAILED", failure.code);
    }
    @Test void leaseLossCancelsTheCommand() {
        var checks = new AtomicInteger();
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> assertThrows(ExecutionFailure.LeaseLost.class,
                () -> new LocalCommands().run(command("wait"), root, Map.of(), Duration.ofSeconds(30),
                        () -> { if (checks.incrementAndGet() > 1) throw new ExecutionFailure.LeaseLost(); }, line -> {}, false)));
    }
    @Test void capturedOutputIsBounded() {
        assertThrows(ExecutionFailure.class, () -> new LocalCommands().run(command("flood"), root, Map.of(), Duration.ofSeconds(10), () -> {}, line -> {}, true));
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("flood")) System.out.print("x".repeat(100000));
            else Thread.sleep(30000);
        }
    }
}
