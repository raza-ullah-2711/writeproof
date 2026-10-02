package com.writeproof;

import static org.assertj.core.api.Assertions.assertThat;

import com.writeproof.handwriting.HandwritingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

/**
 * One-shot commands (the calibration export) run with {@code web-application-type=none}. The
 * application must still start without a servlet container or HTTP security.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NonWebStartupTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithoutAWebServer() {
        assertThat(context.getBean(HandwritingService.class)).isNotNull();
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
    }
}
