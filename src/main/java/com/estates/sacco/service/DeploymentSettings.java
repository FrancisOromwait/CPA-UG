package com.estates.sacco.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How this installation is set up. With single-branch mode on (the CPA Uganda setup) there is one branch only: it is created on
 * first start together with its administrator, the login page shows no branch list, and the branch administrator looks after the
 * chart of accounts and the mobile money numbers (in a multi-branch installation those belong to the manager).
 * Every value has a default that keeps the usual multi-branch behaviour.
 */
@Component
@Getter
public class DeploymentSettings {
    @Value("${sacco.single-branch.enabled:false}")
    private boolean singleBranch;

    @Value("${sacco.single-branch.code:BR001}")
    private String branchCode;

    @Value("${sacco.single-branch.name:Main Branch}")
    private String branchName;

    @Value("${sacco.single-branch.admin-username:admin}")
    private String adminUsername;

    /** The first password of the branch administrator. Empty means one is generated and written to the start-up log. */
    @Value("${sacco.single-branch.admin-password:}")
    private String adminPassword;

    /**
     * Loan interest by the days of the month: a month's interest is the balance at its end x the yearly rate / 365 x the days of that month.
     * Off (the default): a twelfth of the yearly rate, whatever the month.
     */
    @Value("${sacco.loan-interest.daily:false}")
    private boolean loanInterestDaily;

    /** The yearly interest rate (%) the loan form starts with; it can be changed on the form. */
    @Value("${sacco.loan-interest.default-rate:12}")
    private java.math.BigDecimal loanDefaultRate;

    /** What the loan form starts with. The application fee and legal charges are amounts, the process fee and insurance a percentage of the loan. */
    @Value("${sacco.loan-fees.application:0}")
    private java.math.BigDecimal loanApplicationFee;
    @Value("${sacco.loan-fees.process-percent:0}")
    private java.math.BigDecimal loanProcessPercent;
    @Value("${sacco.loan-fees.insurance-percent:0}")
    private java.math.BigDecimal loanInsurancePercent;
    @Value("${sacco.loan-fees.legal-charges:0}")
    private java.math.BigDecimal loanLegalCharges;

    /** The name shown on the login page, the screens and the top of every PDF. */
    @Value("${sacco.app-name:Estates Sacco Manager}")
    private String appName;
}
