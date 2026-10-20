package com.estates.sacco.domain;

public enum Source { CASH, BANK, MOBILE_MONEY, PAYROLL,
    /** Posted by the system itself, with no money moving (monthly interest, corrections). */
    SYSTEM }
