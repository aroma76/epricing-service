package com.bank.epricing.util;

import com.bank.epricing.exception.PricingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PricingCalculatorTest {

    private PricingCalculator pricingCalculator;

    @BeforeEach
    void setUp() {
        pricingCalculator = new PricingCalculator();
    }

    @Test
    @DisplayName("Should calculate interest rate correctly for Home Loan with excellent credit score")
    void testCalculateInterestRate_HomeLoan_ExcellentCredit() {
        // Base rate: 8.50 * 1.0 = 8.50, Credit score 820 -> -1.00 => 7.50%
        BigDecimal rate = pricingCalculator.calculateInterestRate(
            "HOME_LOAN", 820, new BigDecimal("1000000")
        );
        assertEquals(new BigDecimal("7.50"), rate);
    }

    @Test
    @DisplayName("Should apply large loan discount for amounts above 50 Lakhs")
    void testCalculateInterestRate_LargeLoanDiscount() {
        // Base rate: 8.50 * 1.0 = 8.50, Credit score 760 (-0.50) -> 8.00 - 0.25 (large loan) = 7.75%
        BigDecimal rate = pricingCalculator.calculateInterestRate(
            "HOME_LOAN", 760, new BigDecimal("6000000")
        );
        assertEquals(new BigDecimal("7.75"), rate);
    }

    @Test
    @DisplayName("Should calculate EMI accurately")
    void testCalculateEmi() {
        // Principal = 1,000,000, Rate = 8.50%, Tenure = 120 months
        BigDecimal emi = pricingCalculator.calculateEmi(
            new BigDecimal("1000000"), new BigDecimal("8.50"), 120
        );
        assertNotNull(emi);
        assertTrue(emi.compareTo(BigDecimal.ZERO) > 0);
        // EMI should be approximately 12,398.57
        assertEquals(new BigDecimal("12398.57"), emi);
    }

    @Test
    @DisplayName("Should determine risk category based on credit score")
    void testDetermineRiskCategory() {
        assertEquals("LOW", pricingCalculator.determineRiskCategory(820));
        assertEquals("LOW_MEDIUM", pricingCalculator.determineRiskCategory(760));
        assertEquals("MEDIUM", pricingCalculator.determineRiskCategory(710));
        assertEquals("MEDIUM_HIGH", pricingCalculator.determineRiskCategory(660));
        assertEquals("HIGH", pricingCalculator.determineRiskCategory(600));
        assertEquals("MEDIUM", pricingCalculator.determineRiskCategory(null));
    }

    @Test
    @DisplayName("Should throw InsufficientCreditScoreException if credit score is below 650")
    void testValidateEligibility_LowCreditScore() {
        assertThrows(PricingException.InsufficientCreditScoreException.class, () ->
            pricingCalculator.validateEligibility("CUST001234", 620, new BigDecimal("500000"), new BigDecimal("1200000"))
        );
    }

    @Test
    @DisplayName("Should pass eligibility validation for valid credit score and loan amount")
    void testValidateEligibility_Valid() {
        assertDoesNotThrow(() ->
            pricingCalculator.validateEligibility("CUST001234", 750, new BigDecimal("500000"), new BigDecimal("1200000"))
        );
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when EMI tenure is zero or negative")
    void testCalculateEmi_ZeroOrNegativeTenure() {
        assertThrows(IllegalArgumentException.class, () ->
            pricingCalculator.calculateEmi(new BigDecimal("1000000"), new BigDecimal("8.50"), 0)
        );
        assertThrows(IllegalArgumentException.class, () ->
            pricingCalculator.calculateEmi(new BigDecimal("1000000"), new BigDecimal("8.50"), -12)
        );
    }

    @Test
    @DisplayName("Should calculate EMI correctly with 0% interest rate")
    void testCalculateEmi_ZeroRate() {
        BigDecimal emi = pricingCalculator.calculateEmi(new BigDecimal("120000"), BigDecimal.ZERO, 12);
        assertEquals(new BigDecimal("10000.00"), emi);
    }

    @Test
    @DisplayName("Should throw FoirExceededException when EMI exceeds 50% FOIR cap")
    void testValidateFoir_BreachesCap() {
        // Monthly income = 200,000 / 12 = 16,666.67. 50% cap = 8,333.34
        // Proposed EMI = 10,000 > 8,333.34
        assertThrows(PricingException.FoirExceededException.class, () ->
            pricingCalculator.validateFoir("CUST001234", new BigDecimal("10000"), new BigDecimal("200000"))
        );
    }

    @Test
    @DisplayName("Should pass FOIR validation when EMI is within 50% of monthly income")
    void testValidateFoir_Valid() {
        // Monthly income = 1,200,000 / 12 = 100,000. 50% cap = 50,000
        // Proposed EMI = 40,000 <= 50,000
        assertDoesNotThrow(() ->
            pricingCalculator.validateFoir("CUST001234", new BigDecimal("40000"), new BigDecimal("1200000"))
        );
    }

    @Test
    @DisplayName("Should not apply large loan discount at exactly 50 Lakhs threshold boundary")
    void testCalculateInterestRate_50LakhExactBoundary_NoDiscount() {
        // Base rate: 8.50 * 1.0 = 8.50, Credit score 760 (-0.50) -> 8.00%.
        // Exactly 5,000,000 does not exceed 50 Lakhs, so no -0.25% discount.
        BigDecimal rate = pricingCalculator.calculateInterestRate(
            "HOME_LOAN", 760, new BigDecimal("5000000")
        );
        assertEquals(new BigDecimal("8.00"), rate);
    }

    @Test
    @DisplayName("Should apply large loan discount for amounts strictly above 50 Lakhs boundary (50,00,001)")
    void testCalculateInterestRate_Above50LakhBoundary_ReceivesDiscount() {
        // Base rate: 8.50 * 1.0 = 8.50, Credit score 760 (-0.50) -> 8.00 - 0.25 = 7.75%
        BigDecimal rate = pricingCalculator.calculateInterestRate(
            "HOME_LOAN", 760, new BigDecimal("5000001")
        );
        assertEquals(new BigDecimal("7.75"), rate);
    }

    @Test
    @DisplayName("Should throw UnsupportedProductTypeException for unknown loan product")
    void testCalculateInterestRate_UnsupportedProductType() {
        assertThrows(PricingException.UnsupportedProductTypeException.class, () ->
            pricingCalculator.calculateInterestRate("GOLD_LOAN", 750, new BigDecimal("1000000"))
        );
    }

    @Test
    @DisplayName("Should throw LoanAmountExceedsEligibilityException when loan exceeds 10x annual income")
    void testValidateEligibility_Exceeds10xIncome() {
        // Annual income = 1,000,000 -> Max eligible = 10,000,000. Requested = 12,000,000.
        assertThrows(PricingException.LoanAmountExceedsEligibilityException.class, () ->
            pricingCalculator.validateEligibility("CUST001234", 750, new BigDecimal("12000000"), new BigDecimal("1000000"))
        );
    }

    @Test
    @DisplayName("Should clamp interest rate to 7.00% regulatory floor when formula evaluates lower")
    void testCalculateInterestRate_ClampedToFloor() {
        // Artificially lower baseRate to 5.00 to force sub-7% calculation
        ReflectionTestUtils.setField(pricingCalculator, "baseRate", new BigDecimal("5.00"));
        // 5.00 - 1.00 (excellent credit) - 0.25 (large loan) = 3.75% -> clamped to 7.00%
        BigDecimal rate = pricingCalculator.calculateInterestRate(
            "HOME_LOAN", 820, new BigDecimal("6000000")
        );
        assertEquals(new BigDecimal("7.00"), rate);
    }

    @Test
    @DisplayName("Should clamp interest rate to 24.00% regulatory ceiling when formula evaluates higher")
    void testCalculateInterestRate_ClampedToCeiling() {
        // Artificially raise baseRate to 20.00
        ReflectionTestUtils.setField(pricingCalculator, "baseRate", new BigDecimal("20.00"));
        // 20.00 * 1.50 (personal loan) = 30.00 + 1.50 (poor credit) = 31.50% -> clamped to 24.00%
        BigDecimal rate = pricingCalculator.calculateInterestRate(
            "PERSONAL_LOAN", 600, new BigDecimal("1000000")
        );
        assertEquals(new BigDecimal("24.00"), rate);
    }
}
