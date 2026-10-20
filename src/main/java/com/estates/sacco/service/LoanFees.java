package com.estates.sacco.service;

import com.estates.sacco.domain.Loan;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What is taken out of a loan when it is paid out: the application fee, the process fee, insurance and legal charges.
 * The member owes the whole loan; the paying account is credited with the loan less all of these, and each goes to its own account.
 */
public record LoanFees(BigDecimal application, BigDecimal process, BigDecimal insurance, BigDecimal legal) {
    public static final LoanFees NONE = new LoanFees(null, null, null, null);

    public LoanFees {
        application = money(application);
        process = money(process);
        insurance = money(insurance);
        legal = money(legal);
    }

    public static LoanFees of(Loan l) {
        return new LoanFees(l.getApplicationFee(), l.getProcessFee(), l.getInsuranceFee(), l.getLegalCharges());
    }

    public BigDecimal total() {
        return application.add(process).add(insurance).add(legal);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, RoundingMode.HALF_UP);
    }
}
