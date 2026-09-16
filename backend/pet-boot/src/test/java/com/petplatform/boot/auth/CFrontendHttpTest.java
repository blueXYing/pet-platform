package com.petplatform.boot.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real frontend client over HTTP; isolated virgin ID node and fixed WeChat test provider. */
class CFrontendHttpTest {
    @Test
    void frontendRepositoriesRunAgainstRealHttpAndDurableData() throws Exception {
        Path frontend = CAuthHttpTest.HttpFixture.root().resolve("frontend-miniapp");
        Path runner = frontend.resolve("node_modules/tsx/dist/cli.mjs");
        assertTrue(Files.isRegularFile(runner), "Run npm ci in frontend-miniapp before the integration suite");
        var isolated = new CAuthHttpTest();
        isolated.start();
        try {
            Path output = Path.of("target", "surefire-reports", "frontend-chain.txt").toAbsolutePath();
            Files.createDirectories(output.getParent());
            ProcessBuilder builder = new ProcessBuilder("node", runner.toString(),
                    "src/consumer/tests/backend-chain.ts").directory(frontend.toFile())
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("C_TEST_HTTP_ORIGIN",
                    isolated.base.substring(0, isolated.base.length() - "/api/v1/c".length()));
            Process process = builder.start();
            try {
                assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "frontend chain timed out");
                System.out.print(Files.readString(output));
                assertEquals(0, process.exitValue(), "frontend repositories must pass against the real HTTP stack");
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } finally { isolated.stop(); }
    }

}
