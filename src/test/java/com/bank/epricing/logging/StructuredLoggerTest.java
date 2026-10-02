package com.bank.epricing.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructuredLoggerTest {

    @Test
    @DisplayName("Should mask standard 10-char customer ID keeping prefix and suffix")
    void testMaskCustomerId_Standard() {
        assertEquals("CUST****1234", StructuredLogger.maskCustomerId("CUST001234"));
    }

    @Test
    @DisplayName("Should mask short strings with 4 or fewer characters to ****")
    void testMaskCustomerId_Short() {
        assertEquals("****", StructuredLogger.maskCustomerId("1234"));
        assertEquals("****", StructuredLogger.maskCustomerId("ABC"));
        assertEquals("****", StructuredLogger.maskCustomerId("A"));
    }

    @Test
    @DisplayName("Should return **** when customer ID is null")
    void testMaskCustomerId_Null() {
        assertEquals("****", StructuredLogger.maskCustomerId(null));
    }

    @Test
    @DisplayName("Should mask customer IDs with length greater than 4 correctly")
    void testMaskCustomerId_Medium() {
        String masked = StructuredLogger.maskCustomerId("CUST123");
        assertTrue(masked.contains("****"));
        assertFalse(masked.equals("CUST123"));
    }
}
