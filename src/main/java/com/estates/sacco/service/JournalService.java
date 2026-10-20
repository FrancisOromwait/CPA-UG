package com.estates.sacco.service;

import com.estates.sacco.domain.*;
import com.estates.sacco.repo.AccountRepository;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.GeneralLedgerRepository;
import com.estates.sacco.repo.JournalEntryRepository;
import com.estates.sacco.repo.ProductDefRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manual multi-line journals. Every journal must balance. Accounts that are controlled by member transactions
 * (the product accounts: savings, shares, loans, interest receivable) are refused, so the member ledger and the
 * general ledger always agree; use the deposit, payroll and loan screens for those.
 */
@Service
@RequiredArgsConstructor
public class JournalService {
    private final JournalEntryRepository journals;
    private final GeneralLedgerRepository generalLedger;
    private final AccountRepository accounts;
    private final ProductDefRepository products;
    private final BranchRepository branches;
    private final AuditService audit;
    private final BranchScope scope;

    public record Line(Long accountId, String description, BigDecimal debit, BigDecimal credit) {}

    private record Prepared(Account account, String description, BigDecimal debit, BigDecimal credit) {}

    public JournalEntry load(Long id) {
        JournalEntry j = journals.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Journal not found"));
        scope.assertAccess(j.getBranch().getId());
        return j;
    }

    /** Accounts a journal may not use in this branch. */
    public Set<Long> controlledAccountIds(Long branchId) {
        return products.search(branchId).stream().map(p -> p.getGlAccount().getId()).collect(Collectors.toSet());
    }

    @Transactional
    public JournalEntry create(LocalDate date, String reference, String narration, List<Line> lines) {
        Long branchId = scope.writeBranch();
        List<Prepared> prepared = prepare(branchId, lines);

        JournalEntry j = new JournalEntry();
        j.setBranch(branches.findById(branchId).orElseThrow());
        j.setJournalNo(nextNo(branchId));
        fill(j, date, reference, narration, prepared);
        j.setCreatedBy(scope.me().getUsername());
        journals.save(j);
        post(j, prepared);
        return j;
    }

    @Transactional
    public JournalEntry update(Long id, LocalDate date, String reference, String narration, List<Line> lines) {
        JournalEntry j = load(id);
        String before = describe(j);
        List<Prepared> prepared = prepare(j.getBranch().getId(), lines);
        generalLedger.deleteByJournalId(j.getId());
        generalLedger.flush();
        fill(j, date, reference, narration, prepared);
        journals.save(j);
        post(j, prepared);
        audit.log(j.getBranch(), "EDIT", "Journal", j.getId(), "Was: " + before + " | Now: " + describe(j));
        return j;
    }

    @Transactional
    public void delete(Long id) {
        JournalEntry j = load(id);
        String before = describe(j);
        generalLedger.deleteByJournalId(j.getId());
        generalLedger.flush();
        journals.delete(j);
        audit.log(j.getBranch(), "DELETE", "Journal", id, before);
    }

    // ------------------------------------------------------------------ internals
    private List<Prepared> prepare(Long branchId, List<Line> lines) {
        if (lines == null || lines.size() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A journal needs at least two lines");
        }
        Set<Long> controlled = controlledAccountIds(branchId);
        List<Prepared> out = new ArrayList<>();
        BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
        int n = 0;
        for (Line l : lines) {
            n++;
            BigDecimal dr = money(l.debit()), cr = money(l.credit());
            if (dr.signum() < 0 || cr.signum() < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Line " + n + ": amounts cannot be negative");
            }
            if ((dr.signum() > 0) == (cr.signum() > 0)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Line " + n + " needs either a debit or a credit amount, not both or neither");
            }
            if (l.accountId() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Line " + n + ": choose an account");
            Account a = accounts.findById(l.accountId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
            if (!a.isActive()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Line " + n + ": account " + a.getCode() + " is inactive");
            }
            if (controlled.contains(a.getId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Line " + n + ": " + a.getCode() + " " + a.getName()
                        + " is controlled by member transactions. Use the Regular deposit, Payroll or Loan screens for it.");
            }
            debits = debits.add(dr);
            credits = credits.add(cr);
            out.add(new Prepared(a, l.description() == null || l.description().isBlank() ? null : l.description().trim(), dr, cr));
        }
        if (debits.compareTo(credits) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The journal does not balance: debits " + debits.toPlainString()
                    + " and credits " + credits.toPlainString() + " must be equal");
        }
        return out;
    }

    private void fill(JournalEntry j, LocalDate date, String reference, String narration, List<Prepared> prepared) {
        j.setPostedOn(date == null ? LocalDate.now() : date);
        j.setReference(reference == null || reference.isBlank() ? null : reference.trim());
        j.setNarration(narration == null || narration.isBlank() ? null : narration.trim());
        j.setTotalAmount(prepared.stream().map(Prepared::debit).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private void post(JournalEntry j, List<Prepared> prepared) {
        for (Prepared p : prepared) {
            GeneralLedgerLine g = new GeneralLedgerLine();
            g.setBranch(j.getBranch());
            g.setBranchCode(j.getBranch().getCode());
            g.setAccount(p.account());
            g.setJournal(j);
            g.setPostedOn(j.getPostedOn());
            g.setDescription(p.description() != null ? p.description() : j.getNarration());
            g.setDebit(p.debit());
            g.setCredit(p.credit());
            generalLedger.save(g);
        }
    }

    private String nextNo(Long branchId) {
        int last = journals.findFirstByBranchIdOrderByIdDesc(branchId)
                .map(x -> x.getJournalNo().replaceAll("\\D", ""))
                .filter(s -> !s.isEmpty()).map(Integer::parseInt).orElse(0);
        return String.format("JV%04d", last + 1);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String describe(JournalEntry j) {
        return j.getJournalNo() + " on " + j.getPostedOn() + " total " + j.getTotalAmount()
                + (j.getReference() == null ? "" : " ref " + j.getReference());
    }
}
