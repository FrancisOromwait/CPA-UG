package com.estates.sacco.domain;

public enum TxType {
    SAVINGS_DEPOSIT, SAVINGS_WITHDRAWAL,
    SHARE_PURCHASE, SHARE_REFUND,
    MEMBERSHIP_FEE, MEMBERSHIP_REFUND,
    /** Monthly interest earned on savings, owed to the member until it is paid out. */
    SAVINGS_INTEREST,
    /** The accrued savings interest paid into the member's savings (year end): the interest account goes down, savings go up. */
    SAVINGS_INTEREST_PAYOUT,
    /** Savings interest paid out in the old system: only the interest side came over, its savings side is a deposit of its own. Not posted again. */
    SAVINGS_INTEREST_SETTLED,
    LOAN_DISBURSEMENT, LOAN_REPAYMENT,
    /** The interest part of a repayment that came over from the old system, which kept principal and interest apart. Not posted again. */
    LOAN_INTEREST_PAID,
    /** Interest charged on a loan by the monthly interest run (income earned, added to what the member owes). */
    LOAN_INTEREST,
    /** One-off correction that removed interest recorded in full at payout, before monthly accrual began. */
    LOAN_INTEREST_REVERSAL
}
