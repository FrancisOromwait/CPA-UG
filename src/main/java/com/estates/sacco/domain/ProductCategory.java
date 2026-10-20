package com.estates.sacco.domain;

/** What a product is for. Savings, shares and loans are member accounts (they appear in the member ledger). */
public enum ProductCategory { SAVINGS, SHARES, LOAN, INTEREST_RECEIVABLE,
    /** Membership fees paid by the member: kept on the member's ledger, and income of the sacco. */
    MEMBERSHIP,
    /** Interest earned on a member's savings and not yet paid into them: what the sacco owes the member. */
    SAVINGS_INTEREST }
