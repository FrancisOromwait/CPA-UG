package com.estates.sacco.service;

import com.estates.sacco.domain.Account;
import com.estates.sacco.domain.Source;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * The chart accounts the posting engine uses by code. The defaults are the codes of the standard chart; an installation with its
 * own chart of accounts (CPA Uganda) names its own codes in application.properties under sacco.accounts.*
 */
@Component
@Getter
public class AccountCodes {
    @Value("${sacco.accounts.cash:1100}")
    private String cash;
    @Value("${sacco.accounts.bank:1200}")
    private String bank;
    /** The mobile money account used when a deposit does not say which number received it. */
    @Value("${sacco.accounts.mobile-money:1300}")
    private String mobileMoney;
    /** Payroll batches without a chosen receiving account are paid into this account. */
    @Value("${sacco.accounts.payroll:1200}")
    private String payroll;
    /** Loan application fees are income on this account. */
    @Value("${sacco.accounts.loan-fee-income:4200}")
    private String loanFeeIncome;
    /** The other charges taken out of a loan when it is paid out (blank: no such account is set, so the charge cannot be posted). */
    @Value("${sacco.accounts.loan-process-fee-income:}")
    private String loanProcessFeeIncome;
    @Value("${sacco.accounts.loan-insurance:}")
    private String loanInsurance;
    @Value("${sacco.accounts.legal-charges-income:}")
    private String legalChargesIncome;
    @Value("${sacco.accounts.withdrawal-fee-income:4400}")
    private String withdrawalFeeIncome;
    @Value("${sacco.accounts.savings:2100}")
    private String savings;
    @Value("${sacco.accounts.shares:3100}")
    private String shares;
    /** Interest earned on savings is an expense on this account. */
    @Value("${sacco.accounts.savings-interest-expense:5400}")
    private String savingsInterestExpense;
    /** Interest earned on savings is owed to the members on this account until it is paid out (blank: no savings interest product is made). */
    @Value("${sacco.accounts.savings-interest-payable:}")
    private String savingsInterestPayable;
    /** Membership fees are income on this account (blank: no membership product is made). */
    @Value("${sacco.accounts.membership:}")
    private String membership;
    @Value("${sacco.accounts.loans:1400}")
    private String loans;
    @Value("${sacco.accounts.interest-receivable:1500}")
    private String interestReceivable;
    @Value("${sacco.accounts.loan-interest-income:4100}")
    private String loanInterestIncome;

    /** Heading the mobile money numbers are grouped under (blank: no heading). */
    @Value("${sacco.accounts.mobile-money-parent:1300}")
    private String mobileMoneyParent;
    /** The codes handed out to new mobile money numbers. */
    @Value("${sacco.accounts.mobile-money-from:1301}")
    private int mobileMoneyFrom;
    @Value("${sacco.accounts.mobile-money-to:1399}")
    private int mobileMoneyTo;
    /** Other existing accounts that count as mobile money (comma separated). */
    @Value("${sacco.accounts.mobile-money-also:}")
    private String mobileMoneyAlso;

    public String codeFor(Source s) {
        return switch (s) {
            case CASH -> cash;
            case MOBILE_MONEY -> mobileMoney;
            case PAYROLL -> payroll;
            default -> bank;
        };
    }

    /** Cash, mobile money, anything else counts as bank. */
    public Source sourceOf(Account receiving) {
        String code = receiving.getCode();
        if (code.equals(cash)) return Source.CASH;
        return isMobileMoney(code) ? Source.MOBILE_MONEY : Source.BANK;
    }

    public boolean isMobileMoney(String code) {
        if (code.equals(mobileMoney)) return true;
        List<String> also = Arrays.stream(mobileMoneyAlso.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (also.contains(code)) return true;
        try {
            int c = Integer.parseInt(code);
            return c >= mobileMoneyFrom && c <= mobileMoneyTo;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
