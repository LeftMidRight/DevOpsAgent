package org.example.observability;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservabilityNamesTest {

    @Test
    void lookbackDefaultsAndRejectsOutOfRange() {
        assertEquals(Duration.ofMinutes(30), ObservabilityNames.lookback(null).duration());
        assertEquals(Duration.ofHours(6), ObservabilityNames.lookback("360m").duration());
        assertThrows(IllegalArgumentException.class, () -> ObservabilityNames.lookback("7h"));
        assertThrows(IllegalArgumentException.class, () -> ObservabilityNames.lookback("30min"));
    }

    @Test
    void serviceRejectsPromqlBreakout() {
        assertEquals("payment-service", ObservabilityNames.requireService(" payment-service "));
        assertThrows(IllegalArgumentException.class, () -> ObservabilityNames.requireService("pay\"}"));
        assertThrows(IllegalArgumentException.class, () -> ObservabilityNames.requireService(""));
        assertThrows(IllegalArgumentException.class, () -> ObservabilityNames.optionalInstance("host name"));
    }

    @Test
    void keywordEscapesQuotesWithoutLeavingTheLiteral() {
        assertEquals("a\\\"b\\\\c", ObservabilityNames.escapeLogqlLiteral("a\"b\\c"));
        assertThrows(IllegalArgumentException.class, () -> ObservabilityNames.optionalKeyword("bad\nline"));
        assertThrows(IllegalArgumentException.class, () -> ObservabilityNames.optionalLevel("FATAL"));
    }
}
