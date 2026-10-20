package com.estates.sacco.domain;

/** How a loan's interest is worked out. */
public enum InterestMethod {
    /** Interest on the original amount for the whole term (the original method). */
    STRAIGHT_LINE,
    /** Interest each month on what is still owed, with equal monthly installments. */
    REDUCING_BALANCE
}
