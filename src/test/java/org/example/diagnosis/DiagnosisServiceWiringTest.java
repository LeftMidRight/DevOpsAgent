package org.example.diagnosis;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DiagnosisServiceWiringTest {

    @Test
    void springCreatesBeanWhenTwoConstructorsExist() {
        assertDoesNotThrow(() -> {
            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                ctx.register(EvidenceParser.class, DiagnosisService.class);
                ctx.refresh();
                assertNotNull(ctx.getBean(DiagnosisService.class));
            }
        });
    }
}
