package dev.applymcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.applymcp.prequal.backend.HttpPrequalBackend;
import dev.applymcp.prequal.backend.MockPrequalBackend;
import dev.applymcp.prequal.backend.PrequalBackend;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the switch: the same application starts with the mock by default
 * and with the real HTTP backend when the "real" profile is active.
 */
class BackendSelectionTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    class DefaultsToMock {
        @Autowired
        PrequalBackend backend;

        @Test
        void mockIsSelected() {
            assertThat(backend).isInstanceOf(MockPrequalBackend.class);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
            "prequal.http.base-url=http://localhost:9999",
            "prequal.http.client-id=test-client",
            "prequal.http.api-key=test-key"})
    @ActiveProfiles("real")
    class RealProfileSelectsHttp {
        @Autowired
        PrequalBackend backend;

        @Test
        void httpIsSelected() {
            assertThat(backend).isInstanceOf(HttpPrequalBackend.class);
        }
    }
}
