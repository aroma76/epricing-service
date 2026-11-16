package com.bank.epricing.temporal;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.exception.PricingException;
import com.bank.epricing.temporal.service.TemporalPricingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TemporalPricingServiceTest {

    @Test
    @DisplayName("Throws exception when Temporal is disabled")
    void testDisabledTemporalThrowsException() {
        TemporalPricingService service = new TemporalPricingService(Optional.empty());

        PricingRequestDto dto = new PricingRequestDto();
        dto.setCustomerId("CUST001234");
        dto.setLoanAmount(new BigDecimal("1000000"));

        PricingException ex = assertThrows(PricingException.class, () ->
            service.startPricingWorkflow(dto, "127.0.0.1")
        );

        assertEquals("TEMPORAL_DISABLED", ex.getErrorCode());
        assertFalse(service.isTemporalEnabled());
    }
}

