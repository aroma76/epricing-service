package com.bank.epricing.util;

import com.bank.epricing.exception.PricingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  PricingCalculator.java — Pure Business Logic (No Side Effects)         ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  WHY A SEPARATE UTILITY CLASS:                                           ║
 * ║  Pricing calculation logic is PURE MATH — it takes inputs and returns  ║
 * ║  outputs with zero side effects (no DB calls, no HTTP calls, no logs).  ║
 * ║  Keeping it separate from the service enables:                          ║
 * ║    1. Unit testing with no mocks (just call the method with numbers)   ║
 * ║    2. Reuse across multiple services                                     ║
 * ║    3. Clear separation: "This class does math. Nothing else."           ║
 * ║                                                                          ║
 * ║  This is the "Single Responsibility Principle" — one class, one job.   ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@Component
public class PricingCalculator {

    private static final Logger log = LoggerFactory.getLogger(PricingCalculator.class);

    // ═══════════════════════════════════════════════════════════════
    // CONSTANTS — base rates and risk multipliers
    // Configurable base rate and risk multipliers (loaded from application.yml with safe fallbacks)
    @Value("${epricing.pricing.base-rate:8.50}")
    private BigDecimal baseRate = new BigDecimal("8.50");

    @Value("${epricing.pricing.risk-multiplier.home-loan:1.00}")
    private BigDecimal homeLoanMultiplier = new BigDecimal("1.00");

    @Value("${epricing.pricing.risk-multiplier.auto-loan:1.10}")
    private BigDecimal autoLoanMultiplier = new BigDecimal("1.10");

    @Value("${epricing.pricing.risk-multiplier.business-loan:1.30}")
    private BigDecimal businessLoanMultiplier = new BigDecimal("1.30");

    @Value("${epricing.pricing.risk-multiplier.personal-loan:1.50}")
    private BigDecimal personalLoanMultiplier = new BigDecimal("1.50");

    @Value("${epricing.pricing.risk-multiplier.education-loan:1.05}")
    private BigDecimal educationLoanMultiplier = new BigDecimal("1.05");

    // Credit score adjustments:
    // Excellent (800+): -1.00% (reward good credit)
    // Good (750-799):   -0.50%
    // Average (700-749): +0.00% (baseline)
    // Below Average (650-699): +0.50%
    // Poor (<650): +1.50% (high risk premium)
    private static final BigDecimal CREDIT_SCORE_EXCELLENT_ADJUSTMENT = new BigDecimal("-1.00");
    private static final BigDecimal CREDIT_SCORE_GOOD_ADJUSTMENT = new BigDecimal("-0.50");
    private static final BigDecimal CREDIT_SCORE_AVERAGE_ADJUSTMENT = BigDecimal.ZERO;
    private static final BigDecimal CREDIT_SCORE_BELOW_AVERAGE_ADJUSTMENT = new BigDecimal("0.50");
    private static final BigDecimal CREDIT_SCORE_POOR_ADJUSTMENT = new BigDecimal("1.50");

    // Minimum credit score for any loan (RBI guideline)
    public static final int MINIMUM_CREDIT_SCORE = 650;

    /**
     * Calculates the interest rate for a loan.
     *
     * FORMULA:
     * Rate = BASE_RATE × PRODUCT_MULTIPLIER + CREDIT_SCORE_ADJUSTMENT
     *
     * Capped between 7.00% (floor) and 24.00% (ceiling).
     * RBI mandates the ceiling for retail loans.
     *
     * @param productType  Type of loan product
     * @param creditScore  Customer's CIBIL/Experian score (null = use average)
     * @param loanAmount   Principal loan amount (used for amount-based adjustments)
     * @return Calculated interest rate (% per annum), rounded to 2 decimal places
     */
    public BigDecimal calculateInterestRate(
        String productType,
        Integer creditScore,
        BigDecimal loanAmount
    ) {
        log.debug("Calculating interest rate | productType={} | creditScore={} | amount={}",
            productType, creditScore, loanAmount);

        // Step 1: Get product multiplier
        BigDecimal productMultiplier = getProductMultiplier(productType);

        // Step 2: Calculate base product rate
        // baseRate × multiplier → e.g., 8.50 × 1.50 = 12.75% for PERSONAL_LOAN
        BigDecimal productRate = baseRate.multiply(productMultiplier)
            .setScale(2, RoundingMode.HALF_UP);

        // Step 3: Apply credit score adjustment
        BigDecimal creditAdjustment = getCreditScoreAdjustment(
            creditScore != null ? creditScore : 700  // default to "Average" if not provided
        );

        // Step 4: Final rate = product rate + credit adjustment
        BigDecimal finalRate = productRate.add(creditAdjustment);

        // Step 5: Apply large loan discount (economy of scale).
        // Loans > ₹50 lakh (5,000,000) get -0.25% because they're more profitable overall.
        if (loanAmount != null && loanAmount.compareTo(new BigDecimal("5000000")) > 0) {
            finalRate = finalRate.subtract(new BigDecimal("0.25"));
        }

        // Step 6: Cap within regulatory bounds
        BigDecimal floor = new BigDecimal("7.00");
        BigDecimal ceiling = new BigDecimal("24.00");

        if (finalRate.compareTo(floor) < 0) {
            finalRate = floor;
        }
        if (finalRate.compareTo(ceiling) > 0) {
            finalRate = ceiling;
        }

        BigDecimal result = finalRate.setScale(2, RoundingMode.HALF_UP);
        log.debug("Interest rate calculated | rate={}", result);
        return result;
    }

    /**
     * Calculates the Equated Monthly Installment (EMI).
     *
     * EMI FORMULA (standard reducing balance):
     * EMI = P × r × (1+r)^n / [(1+r)^n - 1]
     *
     * Where:
     *   P = Principal (loan amount)
     *   r = Monthly interest rate = annual rate / 12 / 100
     *   n = Number of months (tenure)
     *
     * Example:
     *   P = ₹10,00,000 | r = 8.75%/12/100 = 0.00729 | n = 120
     *   EMI = 10,00,000 × 0.00729 × (1.00729)^120 / [(1.00729)^120 - 1]
     *       = ₹12,577 per month
     *
     * WHY BigDecimal and not double:
     *   EMI calculations in banking must be EXACT.
     *   Over 30 years, a 1-paisa rounding error per month = ₹3.60 total error.
     *   At millions of customers, this becomes significant.
     *   BigDecimal with HALF_UP rounding matches RBI-approved calculation standards.
     */
    public BigDecimal calculateEmi(BigDecimal principalAmount, BigDecimal annualRatePercent, int tenureMonths) {

        // Convert annual rate % to monthly decimal: 8.75% → 8.75/12/100 = 0.007292
        BigDecimal monthlyRate = annualRatePercent
            .divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP)  // /12
            .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP); // /100

        // If rate is 0 (hypothetically), EMI is simply P/n (no interest)
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principalAmount.divide(BigDecimal.valueOf(tenureMonths), 2, RoundingMode.HALF_UP);
        }

        // (1 + r)^n — compound interest factor
        // MathContext.DECIMAL128 provides 34 digits of precision — more than enough for banking
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal compoundFactor = onePlusR.pow(tenureMonths, MathContext.DECIMAL128);

        // Numerator: P × r × (1+r)^n
        BigDecimal numerator = principalAmount
            .multiply(monthlyRate)
            .multiply(compoundFactor);

        // Denominator: (1+r)^n - 1
        BigDecimal denominator = compoundFactor.subtract(BigDecimal.ONE);

        // EMI = Numerator / Denominator
        BigDecimal emi = numerator.divide(denominator, 2, RoundingMode.HALF_UP);

        log.debug("EMI calculated | principal={} | rate={} | tenure={} | emi={}",
            principalAmount, annualRatePercent, tenureMonths, emi);

        return emi;
    }

    /**
     * Determines risk category based on credit score.
     * Used in PricingResponseDto to explain the rate to the customer.
     */
    public String determineRiskCategory(Integer creditScore) {
        if (creditScore == null) return "MEDIUM";
        if (creditScore >= 800) return "LOW";
        if (creditScore >= 750) return "LOW_MEDIUM";
        if (creditScore >= 700) return "MEDIUM";
        if (creditScore >= 650) return "MEDIUM_HIGH";
        return "HIGH";
    }

    /**
     * Validates whether a customer is eligible for a loan.
     * Throws a specific exception if not eligible (caught by GlobalExceptionHandler).
     */
    public void validateEligibility(String customerId, Integer creditScore, BigDecimal loanAmount, BigDecimal annualIncome) {
        // Minimum credit score check
        if (creditScore != null && creditScore < MINIMUM_CREDIT_SCORE) {
            throw new PricingException.InsufficientCreditScoreException(
                customerId, creditScore, MINIMUM_CREDIT_SCORE
            );
        }

        // FOIR check: EMI should not exceed 50% of monthly income
        // (FOIR = Fixed Obligation to Income Ratio — RBI guideline)
        if (annualIncome != null && loanAmount != null) {
            BigDecimal monthlyIncome = annualIncome.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            BigDecimal maxEligibleLoan = monthlyIncome
                .multiply(new BigDecimal("0.50"))  // 50% FOIR
                .multiply(BigDecimal.valueOf(120)) // assuming 10 year max term for eligibility
                .multiply(new BigDecimal("0.85")); // 85% of calculated max (safety margin)

            if (loanAmount.compareTo(maxEligibleLoan) > 0) {
                throw new PricingException.LoanAmountExceedsEligibilityException(
                    customerId,
                    loanAmount.doubleValue(),
                    maxEligibleLoan.doubleValue()
                );
            }
        }
    }

    // ─── PRIVATE HELPERS ──────────────────────────────────────────────────

    private BigDecimal getProductMultiplier(String productType) {
        return switch (productType.toUpperCase()) {
            case "HOME_LOAN"      -> homeLoanMultiplier;
            case "AUTO_LOAN"      -> autoLoanMultiplier;
            case "BUSINESS_LOAN"  -> businessLoanMultiplier;
            case "PERSONAL_LOAN"  -> personalLoanMultiplier;
            case "EDUCATION_LOAN" -> educationLoanMultiplier;
            default -> throw new PricingException.UnsupportedProductTypeException(productType);
        };
    }

    private BigDecimal getCreditScoreAdjustment(int creditScore) {
        if (creditScore >= 800) return CREDIT_SCORE_EXCELLENT_ADJUSTMENT;
        if (creditScore >= 750) return CREDIT_SCORE_GOOD_ADJUSTMENT;
        if (creditScore >= 700) return CREDIT_SCORE_AVERAGE_ADJUSTMENT;
        if (creditScore >= 650) return CREDIT_SCORE_BELOW_AVERAGE_ADJUSTMENT;
        return CREDIT_SCORE_POOR_ADJUSTMENT;
    }
}
