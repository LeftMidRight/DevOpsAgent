package org.example.observability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeriesMathTest {

    @Test
    void summarizesTrendWithoutTreatingZeroAsMissing() {
        SeriesMath.Summary rising = SeriesMath.summarize(List.of(0.02, 0.04, 0.08));
        assertEquals(0.08, rising.current());
        assertEquals(0.047, rising.average());
        assertEquals(0.08, rising.max());
        assertEquals("rising", rising.trend());

        SeriesMath.Summary zeros = SeriesMath.summarize(List.of(0.0, 0.0, 0.0));
        assertEquals(0.0, zeros.current());
        assertEquals("stable", zeros.trend());

        assertNull(SeriesMath.summarize(List.of()));
        assertEquals("unknown", SeriesMath.summarize(List.of(1.0)).trend());
        assertEquals("falling", SeriesMath.summarize(List.of(100.0, 50.0)).trend());
        assertEquals("stable", SeriesMath.summarize(List.of(100.0, 110.0)).trend());
    }
}
