package com.omniforge.core.eula;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EulaServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void 未同意与同意状态流转() {
        EulaService service = new EulaService(tempDir);
        assertFalse(service.isAccepted(), "初始应为未同意");

        service.accept();
        assertTrue(service.isAccepted(), "同意后状态持久化");
        assertTrue(Files.exists(tempDir.resolve("eula.accepted")));

        // 新实例（重启场景）仍为已同意
        assertTrue(new EulaService(tempDir).isAccepted());
    }
}
