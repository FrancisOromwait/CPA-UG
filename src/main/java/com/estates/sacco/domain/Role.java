package com.estates.sacco.domain;

/**
 * MANAGER sees every branch. BRANCH_ADMIN runs one branch and creates its staff. TREASURER and CASHIER work in one branch
 * with the same rights as a branch admin, except that they cannot manage users. MEMBER is a member's own login: it can only
 * view and download that member's statement.
 */
public enum Role {
    MANAGER, BRANCH_ADMIN, TREASURER, CASHIER, MEMBER;

    /** Staff created by a branch admin. */
    public boolean isStaff() {
        return this == TREASURER || this == CASHIER;
    }
}
