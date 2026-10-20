package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.AccountRepository;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.MemberLedgerRepository;
import com.estates.sacco.repo.ProductDefRepository;
import com.estates.sacco.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Turns every ledger entry into its member-ledger (ML) line and balanced general-ledger (GL) lines,
 * in the same database transaction as the entry itself.
 *
 * <pre>
 * Savings deposit / share purchase : Dr cash-side account   Cr product account      (ML credit)
 * Savings withdrawal / share refund: Dr product account     Cr cash-side account    (ML debit)
 * Loan disbursement                : Dr loans (principal)   Cr cash-side account      (ML debit: principal only)
 * Monthly interest (interest run)  : Dr interest receivable Cr interest income        (ML debit: the interest)
 * Loan repayment                   : Dr cash-side account   Cr interest receivable    (interest fallen due, paid first)
 *                                                           Cr loans (the rest)       (ML credit: the whole payment)
 * Interest reversal (one-off)      : Dr interest income     Cr interest receivable    (ML credit)
 * </pre>
 * The cash-side account is the receiving account chosen on the transaction (payroll batches always choose one);
 * otherwise it follows from how the money moved: the cash, bank or mobile money account named in {@link AccountCodes}.
 */
@Service
@RequiredArgsConstructor
public class PostingService {
    private final AccountRepository accounts;
    private final ProductDefRepository products;
    private final MemberLedgerRepository memberLedger;
    private final GeneralLedgerRepository generalLedger;
    private final LedgerRepository ledger;
    private final LoanScheduleService schedules;

    private final AccountCodes codes;

    public static ProductCategory categoryOf(TxType t) {
        return switch (t) {
            case SAVINGS_DEPOSIT, SAVINGS_WITHDRAWAL -> ProductCategory.SAVINGS;
            case SHARE_PURCHASE, SHARE_REFUND -> ProductCategory.SHARES;
            case MEMBERSHIP_FEE, MEMBERSHIP_REFUND -> ProductCategory.MEMBERSHIP;
            case SAVINGS_INTEREST, SAVINGS_INTEREST_PAYOUT, SAVINGS_INTEREST_SETTLED -> ProductCategory.SAVINGS_INTEREST;
            case LOAN_DISBURSEMENT, LOAN_REPAYMENT, LOAN_INTEREST, LOAN_INTEREST_REVERSAL, LOAN_INTEREST_PAID -> ProductCategory.LOAN;
        };
    }

    @Transactional
    public void record(LedgerEntry e) {
        Branch branch = e.getBranch();
        ProductCategory cat = categoryOf(e.getType());
        // the product chosen on the transaction, otherwise the branch default for the category (oldest active)
        ProductDef product = e.getProduct() != null ? e.getProduct()
                : products.findFirstByBranchIdAndCategoryAndActiveTrueOrderByIdAsc(branch.getId(), cat)
                .orElseThrow(() -> unprocessable("No active " + cat.name().toLowerCase() + " product is defined for this branch"));
        if (product.getCategory() != cat) throw unprocessable("Product " + product.getProductCode() + " is not a " + cat.name().toLowerCase() + " product");
        if (!product.isActive()) throw unprocessable("Product " + product.getProductCode() + " is inactive");
        if (e.getProduct() == null) {
            e.setProduct(product);
            ledger.save(e);
        }
        Account productAccount = usable(product.getGlAccount());
        // interest postings move no money, so there is no cash-side account
        // a repayment made by the system (a loan cleared by a top-up) moves no cash either
        boolean noCash = e.getType() == TxType.LOAN_INTEREST || e.getType() == TxType.LOAN_INTEREST_REVERSAL || e.getType() == TxType.LOAN_INTEREST_PAID
                || e.getType() == TxType.SAVINGS_INTEREST || e.getType() == TxType.SAVINGS_INTEREST_PAYOUT || e.getType() == TxType.SAVINGS_INTEREST_SETTLED
                || (e.getType() == TxType.LOAN_REPAYMENT && e.getSource() == Source.SYSTEM);
        Account cash = noCash ? null : cashAccount(e);
        // parts of one regular deposit: the receiving account is debited once for the total (refreshGroupCash)
        boolean grouped = e.getDepositGroup() != null;
        BigDecimal amt = e.getAmount();
        String desc = describe(e);

        switch (e.getType()) {
            case SAVINGS_DEPOSIT, SHARE_PURCHASE, MEMBERSHIP_FEE -> {
                if (!grouped) gl(e, cash, product, desc, amt, BigDecimal.ZERO);
                gl(e, productAccount, product, desc, BigDecimal.ZERO, amt);
                ml(e, product, desc, BigDecimal.ZERO, amt);
            }
            case SAVINGS_WITHDRAWAL, SHARE_REFUND, MEMBERSHIP_REFUND -> {
                // The member's savings go down by the whole amount. A withdrawal fee stays with the sacco as income, so the
                // paying account is credited with the amount less the fee.
                BigDecimal fee = e.getType() == TxType.SAVINGS_WITHDRAWAL && e.getFee() != null ? e.getFee().min(amt) : BigDecimal.ZERO;
                gl(e, productAccount, product, desc, amt, BigDecimal.ZERO);
                gl(e, cash, product, desc, BigDecimal.ZERO, amt.subtract(fee));
                if (fee.signum() > 0) {
                    Account income = usable(accounts.findByCode(codes.getWithdrawalFeeIncome())
                            .orElseThrow(() -> unprocessable("Chart of accounts has no account " + codes.getWithdrawalFeeIncome() + " (Savings Withdrawal Fees)")));
                    gl(e, income, product, "Withdrawal fee - " + desc, BigDecimal.ZERO, fee);
                }
                ml(e, product, desc, amt, BigDecimal.ZERO);
            }
            case LOAN_DISBURSEMENT -> {
                // The member owes the whole principal. The charges (application fee, process fee, insurance, legal charges) are kept
                // out of what is paid: the paying account is credited with the net amount only, and each charge goes to its own
                // account. Interest is charged monthly.
                LoanFees fees = e.getLoan() == null ? LoanFees.NONE : LoanFees.of(e.getLoan());
                gl(e, productAccount, product, desc, amt, BigDecimal.ZERO);
                // a top-up also clears the earlier loan: that part is not paid out in cash
                BigDecimal cleared = e.getLoan() == null || e.getLoan().getTopUpSettled() == null ? BigDecimal.ZERO : e.getLoan().getTopUpSettled();
                gl(e, cash, product, desc, BigDecimal.ZERO, amt.subtract(fees.total()).subtract(cleared));
                loanCharge(e, product, desc, fees.application(), codes.getLoanFeeIncome(), "Loan application fee");
                loanCharge(e, product, desc, fees.process(), codes.getLoanProcessFeeIncome(), "Loan process fee");
                loanCharge(e, product, desc, fees.insurance(), codes.getLoanInsurance(), "Loan insurance");
                loanCharge(e, product, desc, fees.legal(), codes.getLegalChargesIncome(), "Legal charges");
                ml(e, product, desc, amt, BigDecimal.ZERO);
            }
            case LOAN_REPAYMENT -> {
                // interest that has fallen due is paid first, the rest reduces the loan
                BigDecimal interestPart = schedules.interestPortionOf(e.getLoan(), e).min(amt);
                BigDecimal principalPart = amt.subtract(interestPart);
                if (!grouped && cash != null) gl(e, cash, product, desc, amt, BigDecimal.ZERO);
                if (principalPart.signum() > 0) gl(e, productAccount, product, desc, BigDecimal.ZERO, principalPart);
                if (interestPart.signum() > 0) {
                    ProductDef interestProduct = interestProductOf(product);
                    gl(e, usable(interestProduct.getGlAccount()), interestProduct, desc, BigDecimal.ZERO, interestPart);
                }
                ml(e, product, desc, BigDecimal.ZERO, amt);
            }
            case LOAN_INTEREST -> {
                ProductDef interestProduct = interestProductOf(product);
                Account income = loanAccount(product.getInterestIncomeAccount(), "interest income account");
                gl(e, usable(interestProduct.getGlAccount()), interestProduct, desc, amt, BigDecimal.ZERO);
                gl(e, income, product, desc, BigDecimal.ZERO, amt);
                ml(e, product, desc, amt, BigDecimal.ZERO);
            }
            case LOAN_INTEREST_PAID -> throw unprocessable("Interest paid entries come from the old system and cannot be posted here");
            case SAVINGS_INTEREST_SETTLED -> throw unprocessable("Savings interest settled entries come from the old system and cannot be posted here");
            case SAVINGS_INTEREST -> {
                // interest earned on savings: an expense of the sacco, owed to the member until it is paid into their savings
                Account expense = usable(accounts.findByCode(codes.getSavingsInterestExpense())
                        .orElseThrow(() -> unprocessable("Chart of accounts has no account " + codes.getSavingsInterestExpense() + " (Savings Interest Expense)")));
                gl(e, expense, product, desc, amt, BigDecimal.ZERO);
                gl(e, productAccount, product, desc, BigDecimal.ZERO, amt);
                ml(e, product, desc, BigDecimal.ZERO, amt);
            }
            case SAVINGS_INTEREST_PAYOUT -> {
                // what the sacco owed the member becomes part of their savings
                ProductDef savings = products.findFirstByBranchIdAndCategoryAndActiveTrueOrderByIdAsc(branch.getId(), ProductCategory.SAVINGS)
                        .orElseThrow(() -> unprocessable("No active savings product is defined for this branch"));
                gl(e, productAccount, product, desc, amt, BigDecimal.ZERO);
                gl(e, usable(savings.getGlAccount()), savings, desc, BigDecimal.ZERO, amt);
                ml(e, product, desc, amt, BigDecimal.ZERO);
                ml(e, savings, desc, BigDecimal.ZERO, amt);
            }
            case LOAN_INTEREST_REVERSAL -> {
                ProductDef interestProduct = interestProductOf(product);
                Account income = loanAccount(product.getInterestIncomeAccount(), "interest income account");
                gl(e, income, product, desc, amt, BigDecimal.ZERO);
                gl(e, usable(interestProduct.getGlAccount()), interestProduct, desc, BigDecimal.ZERO, amt);
                ml(e, product, desc, BigDecimal.ZERO, amt);
            }
        }
        if (grouped) refreshGroupCash(e.getDepositGroup());
    }

    /** One charge taken out of a loan's payout, credited to its own chart account (nothing is posted for an amount of nothing). */
    private void loanCharge(LedgerEntry e, ProductDef product, String desc, BigDecimal amount, String code, String label) {
        if (amount.signum() <= 0) return;
        if (code == null || code.isBlank()) throw unprocessable("No chart account is set for " + label.toLowerCase());
        Account income = usable(accounts.findByCode(code)
                .orElseThrow(() -> unprocessable("Chart of accounts has no account " + code + " (" + label + ")")));
        gl(e, income, product, label + " - " + desc, BigDecimal.ZERO, amount);
    }

    /** The interest credited to the interest receivable account when this repayment was posted. */
    public BigDecimal postedInterestPortion(LedgerEntry e) {
        ProductDef product = e.getProduct();
        if (product == null || product.getInterestProduct() == null) return BigDecimal.ZERO;
        Long interestAccount = product.getInterestProduct().getGlAccount().getId();
        return generalLedger.findByLedgerEntryIdOrderByIdAsc(e.getId()).stream()
                .filter(g -> g.getAccount().getId().equals(interestAccount))
                .map(GeneralLedgerLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Account cashAccount(LedgerEntry e) {
        return usable(e.getReceivingAccount() != null ? e.getReceivingAccount()
                : accounts.findByCode(codes.codeFor(e.getSource()))
                .orElseThrow(() -> unprocessable("Chart of accounts has no account " + codes.codeFor(e.getSource()))));
    }

    /**
     * Rebuilds the one receiving-account debit of a regular deposit: the total of whatever parts it has now.
     * Called after a part is posted, edited or deleted; with no parts left the line simply disappears.
     */
    @Transactional
    public void refreshGroupCash(String group) {
        generalLedger.deleteByDepositGroup(group);
        generalLedger.flush();
        List<LedgerEntry> parts = ledger.findByDepositGroupOrderByIdAsc(group);
        if (parts.isEmpty()) return;
        LedgerEntry first = parts.get(0);
        BigDecimal total = parts.stream().map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String ref = first.getReference() == null || first.getReference().isBlank() ? "Regular deposit" : first.getReference();
        GeneralLedgerLine g = new GeneralLedgerLine();
        g.setBranch(first.getBranch());
        g.setBranchCode(first.getBranch().getCode());
        g.setAccount(cashAccount(first));
        g.setLedgerEntry(first);
        g.setMember(first.getMember());
        g.setPostedOn(first.getPostedOn());
        g.setDescription(parts.size() > 1 ? ref + " (" + parts.size() + " parts)" : describe(first));
        g.setDebit(total);
        g.setCredit(BigDecimal.ZERO);
        g.setDepositGroup(group);
        generalLedger.save(g);
    }

    /** Joins already-posted deposit parts into one deposit and re-posts them with a single receiving-account debit. */
    @Transactional
    public void regroup(List<Long> entryIds, String group) {
        List<LedgerEntry> parts = ledger.findAllById(entryIds);
        for (LedgerEntry p : parts) {
            generalLedger.deleteByLedgerEntryId(p.getId());
            memberLedger.deleteByLedgerEntryId(p.getId());
        }
        generalLedger.flush();
        for (LedgerEntry p : parts) {
            p.setDepositGroup(group);
            ledger.save(p);
        }
        for (LedgerEntry p : parts) record(p);
    }

    private ProductDef interestProductOf(ProductDef loanProduct) {
        ProductDef ip = loanProduct.getInterestProduct();
        if (ip == null || !ip.isActive()) {
            throw unprocessable("Loan product " + loanProduct.getProductCode() + " has no active interest receivable product set");
        }
        return ip;
    }

    private Account loanAccount(Account a, String what) {
        if (a == null) throw unprocessable("The loan product has no " + what + " set");
        return usable(a);
    }

    private Account usable(Account a) {
        if (!a.isActive()) throw unprocessable("Account " + a.getCode() + " " + a.getName() + " is inactive");
        return a;
    }

    private static String describe(LedgerEntry e) {
        String t = e.getType().name().replace('_', ' ').toLowerCase();
        t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        return e.getReference() == null || e.getReference().isBlank() ? t : t + " - " + e.getReference();
    }

    private void gl(LedgerEntry e, Account account, ProductDef product, String desc, BigDecimal debit, BigDecimal credit) {
        GeneralLedgerLine g = new GeneralLedgerLine();
        g.setBranch(e.getBranch());
        g.setBranchCode(e.getBranch().getCode());
        g.setAccount(account);
        g.setLedgerEntry(e);
        g.setMember(e.getMember());
        g.setProductCode(product == null ? null : product.getProductCode());
        g.setPostedOn(e.getPostedOn());
        g.setDescription(desc);
        g.setDebit(debit);
        g.setCredit(credit);
        generalLedger.save(g);
    }

    private void ml(LedgerEntry e, ProductDef product, String desc, BigDecimal debit, BigDecimal credit) {
        MemberLedgerLine m = new MemberLedgerLine();
        m.setBranch(e.getBranch());
        m.setBranchCode(e.getBranch().getCode());
        m.setMember(e.getMember());
        m.setProduct(product);
        m.setProductCode(product.getProductCode());
        m.setCategory(product.getCategory());
        m.setLedgerEntry(e);
        m.setPostedOn(e.getPostedOn());
        m.setDescription(desc);
        m.setDebit(debit);
        m.setCredit(credit);
        memberLedger.save(m);
    }

    private static ApiException unprocessable(String msg) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }
}
