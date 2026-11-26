package com.bank.epricing.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PricingRequestDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private PricingRequestDto buildValidDtoWithCustomerId(String customerId) {
        return PricingRequestDto.builder()
                .customerId(customerId)
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("5000000.00"))
                .loanTenureMonths(240)
                .creditScore(780)
                .annualIncome(new BigDecimal("2400000.00"))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CUST000001",
            "CUST0012345678",
            "CUST-LIVE-001",
            "CUST-ACT-001",
            "CUST-LIVE-COLLIDE-01",
            "CUSTA1B2C3",
            "CUST-1234567890-ABCDEFGHIJKLMNOPQRSTUVWXYZ-1234567" // exactly 50 chars
    })
    @DisplayName("R3: Valid legacy numeric and enterprise hyphenated customer IDs should pass validation")
    void testValidCustomerIds(String customerId) {
        assertTrue(customerId.length() >= 10 && customerId.length() <= 50,
                "Test fixture length check failed for: " + customerId);

        Set<ConstraintViolation<PricingRequestDto>> violations =
                validator.validate(buildValidDtoWithCustomerId(customerId));
        assertTrue(violations.isEmpty(),
                "Expected no violations for valid customerId '" + customerId + "', got: " + violations);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "INVALID_ID",
            "USER000001",
            "cust000001",
            "CUST-live-001",
            "CUST_LIVE_001",
            "CUST 00001",
            "CUST------",
            "CUST01",
            "CUST00001", // 9 chars (below min 10)
            "CUST-1234567890-ABCDEFGHIJKLMNOPQRSTUVWXYZ-12345678" // 51 chars (above VARCHAR(50))
    })
    @DisplayName("R3: Invalid customer IDs (missing prefix, lowercase, symbols, all-hyphens, out-of-bounds length) should fail validation")
    void testInvalidCustomerIds(String customerId) {
        Set<ConstraintViolation<PricingRequestDto>> violations =
                validator.validate(buildValidDtoWithCustomerId(customerId));
        assertFalse(violations.isEmpty(),
                "Expected validation violation for invalid customerId '" + customerId + "'");
        assertTrue(violations.stream().anyMatch(v -> "customerId".equals(v.getPropertyPath().toString())),
                "Expected violation on 'customerId' property for: " + customerId);
    }
}
