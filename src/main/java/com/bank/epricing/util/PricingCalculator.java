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
 * Pure calculation logic for interest rates and EMIs.
 * No side effects — no DB calls, no HTTP, no metrics.
 * Kept separate so it can be unit-tested without any mocks.
 */
@Component
public class PricingCalculator {

    private static final Logger log = LoggerFactory.getLogger(PricingCalculator.class);

    // Base rates and multipliers — configurable via application.yml
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

    // Credit score adjustments (basis points on the rate)
    private static final BigDecimal CREDIT_SCORE_EXCELLENT_ADJUSTMENT = new BigDecimal("-1.00");
    private static final BigDecimal CREDIT_SCORE_GOOD_ADJUSTMENT = new BigDecimal("-0.50");
    private static final BigDecimal CREDIT_SCORE_AVERAGE_ADJUSTMENT = BigDecimal.ZERO;
    private static final BigDecimal CREDIT_SCORE_BELOW_AVERAGE_ADJUSTMENT = new BigDecimal("0.50");
    private static final BigDecimal CREDIT_SCORE_POOR_ADJUSTMENT = new BigDecimal("1.50");

    // RBI minimum credit score for retail loans
    public static final int MINIMUM_CREDIT_SCORE = 650;

    /**
     * Calculates the annual interest rate for a loan.
     *
     * Formula: Rate = BASE_RATE × PRODUCT_MULTIPLIER + CREDIT_SCORE_ADJUSTMENT
     * Capped between 7.00% (floor) and 24.00% (ceiling) per RBI guidelines.
     */
    public BigDecimal calculateInterestRate(
        String productType,
        Integer creditScore,
        BigDecimal loanAmount
    ) {
        log.debug("Calculating interest rate | productType={} | creditScore={} | amount={}",
            productType, creditScore, loanAmount);

        BigDecimal productMultiplier = getProductMultiplier(productType);
        BigDecimal productRate = baseRate.multiply(productMultiplier)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal creditAdjustment = getCreditScoreAdjustment(
            creditScore != null ? creditScore : 700
        );

        BigDecimal finalRate = productRate.add(creditAdjustment);

        // Loans > ₹50 lakh get a small discount (economy of scale)
        if (loanAmount != null && loanAmount.compareTo(new BigDecimal("5000000")) > 0) {
            finalRate = finalRate.subtract(new BigDecimal("0.25"));
        }

        BigDecimal floor = new BigDecimal("7.00");
        BigDecimal ceiling = new BigDecimal("24.00");
        if (finalRate.compareTo(floor) < 0) finalRate = floor;
        if (finalRate.compareTo(ceiling) > 0) finalRate = ceiling;

        BigDecimal result = finalRate.setScale(2, RoundingMode.HALF_UP);
        log.debug("Interest rate calculated | rate={}", result);
        return result;
    }

    /**
     * Calculates the Equated Monthly Installment (EMI) using standard reducing-balance formula:
     *
     *   EMI = P × r × (1+r)^n / [(1+r)^n - 1]
     *
     * where P = principal, r = monthly rate (annual% / 12 / 100), n = tenure in months.
     * BigDecimal used throughout to avoid floating-point drift on large loan amounts.
     */
    public BigDecimal calculateEmi(BigDecimal principalAmount, BigDecimal annualRatePercent, int tenureMonths) {

        BigDecimal monthlyRate = annualRatePercent
            .divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP)
            .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principalAmount.divide(BigDecimal.valueOf(tenureMonths), 2, RoundingMode.HALF_UP);
        }

        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal compoundFactor = onePlusR.pow(tenureMonths, MathContext.DECIMAL128);

        BigDecimal numerator = principalAmount.multiply(monthlyRate).multiply(compoundFactor);
        BigDecimal denominator = compoundFactor.subtract(BigDecimal.ONE);
        BigDecimal emi = numerator.divide(denominator, 2, RoundingMode.HALF_UP);

        log.debug("EMI calculated | principal={} | rate={} | tenure={} | emi={}",
            principalAmount, annualRatePercent, tenureMonths, emi);

        return emi;
    }

    /**
     * Maps credit score to a risk bucket label used in the response DTO.
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
     * Validates loan eligibility. Throws a typed PricingException if not eligible.
     * Checks: minimum CIBIL score and FOIR (Fixed Obligation to Income Ratio).
     */
    public void validateEligibility(String customerId, Integer creditScore, BigDecimal loanAmount, BigDecimal annualIncome) {
        if (creditScore != null && creditScore < MINIMUM_CREDIT_SCORE) {
            throw new PricingException.InsufficientCreditScoreException(
                customerId, creditScore, MINIMUM_CREDIT_SCORE
            );
        }

        // FOIR check: estimated EMI obligation should not exceed 50% of monthly income
        if (annualIncome != null && loanAmount != null) {
            BigDecimal monthlyIncome = annualIncome.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            BigDecimal maxEligibleLoan = monthlyIncome
                .multiply(new BigDecimal("0.50"))
                .multiply(BigDecimal.valueOf(120))
                .multiply(new BigDecimal("0.85"));

            if (loanAmount.compareTo(maxEligibleLoan) > 0) {
                throw new PricingException.LoanAmountExceedsEligibilityException(
                    customerId,
                    loanAmount.doubleValue(),
                    maxEligibleLoan.doubleValue()
                );
            }
        }
    }

    // --- private helpers ---

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
