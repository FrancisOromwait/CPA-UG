package com.estates.sacco.service;

import com.estates.sacco.domain.AppUser;
import com.estates.sacco.domain.LedgerEntry;
import com.estates.sacco.domain.Role;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.MemberLedgerRepository;
import com.estates.sacco.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * First-run setup: the initial manager login, the standard chart of accounts and product definitions for any
 * branch lacking them, and posting of any older transactions that are not yet in the member and general ledgers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BootstrapRunner implements ApplicationRunner {
    private final UserRepository users;
    private final BranchRepository branches;
    private final AccountService accountService;
    private final ProductService productService;
    private final PostingService postingService;
    private final LedgerRepository ledger;
    private final MemberLedgerRepository memberLedger;
    private final com.estates.sacco.repo.LoanRepository loanRepository;
    private final LoanScheduleService loanSchedules;
    private final InterestRunService interestRuns;
    private final PasswordEncoder encoder;
    private final MemberLoginService memberLogins;
    private final DeploymentSettings settings;
    private final com.estates.sacco.repo.MemberRepository memberRepository;

    @Value("${sacco.bootstrap.manager.username}")
    private String username;
    @Value("${sacco.bootstrap.manager.password}")
    private String password;

    /**
     * Deposits recorded before parts were tied together: entries of one member, date, receipt and receiving account
     * created within seconds of each other were one deposit. Join them so the receiving account is debited once.
     */
    private void regroupEarlierDeposits() {
        List<LedgerEntry> candidates = ledger.findUngroupedDeposits(List.of(com.estates.sacco.domain.TxType.SAVINGS_DEPOSIT,
                com.estates.sacco.domain.TxType.SHARE_PURCHASE, com.estates.sacco.domain.TxType.LOAN_REPAYMENT));
        List<List<LedgerEntry>> clusters = new java.util.ArrayList<>();
        List<LedgerEntry> current = new java.util.ArrayList<>();
        for (LedgerEntry e : candidates) {
            LedgerEntry prev = current.isEmpty() ? null : current.get(current.size() - 1);
            boolean same = prev != null && prev.getMember().getId().equals(e.getMember().getId())
                    && prev.getPostedOn().equals(e.getPostedOn()) && java.util.Objects.equals(prev.getReference(), e.getReference())
                    && java.util.Objects.equals(prev.getReceivingAccount() == null ? null : prev.getReceivingAccount().getId(),
                            e.getReceivingAccount() == null ? null : e.getReceivingAccount().getId())
                    && java.time.Duration.between(prev.getCreatedAt(), e.getCreatedAt()).abs().getSeconds() <= 10;
            if (!same && !current.isEmpty()) { clusters.add(current); current = new java.util.ArrayList<>(); }
            current.add(e);
        }
        if (!current.isEmpty()) clusters.add(current);
        for (List<LedgerEntry> c : clusters) {
            if (c.size() < 2) continue;
            postingService.regroup(c.stream().map(LedgerEntry::getId).toList(), java.util.UUID.randomUUID().toString());
            log.info("Joined {} earlier deposit parts of member {} into one deposit", c.size(), c.get(0).getMember().getMemberNo());
        }
    }

    /** A password from the settings, or (when none is set) a random one that is written once to the start-up log. */
    private String initialPassword(String configured, String who) {
        if (configured != null && !configured.isBlank()) return configured;
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder pw = new StringBuilder();
        for (int i = 0; i < 12; i++) pw.append(chars.charAt(random.nextInt(chars.length())));
        log.warn("==> First-run password for the {}: {}   (write it down; change it after signing in)", who, pw);
        return pw.toString();
    }

    /** Single-branch installation: the one branch and its administrator are created on the first start. */
    private void seedSingleBranch() {
        com.estates.sacco.domain.Branch branch = branches.findAll().stream().findFirst().orElse(null);
        if (branch == null) {
            branch = new com.estates.sacco.domain.Branch();
            branch.setCode(settings.getBranchCode());
            branch.setName(settings.getBranchName());
            branch = branches.save(branch);
            log.warn("Created the branch {} ({})", branch.getName(), branch.getCode());
        }
        final Long branchId = branch.getId();
        boolean hasAdmin = users.findByBranchId(branchId).stream().anyMatch(u -> u.getRole() == Role.BRANCH_ADMIN);
        if (!hasAdmin) {
            AppUser admin = new AppUser();
            admin.setUsername(settings.getAdminUsername());
            admin.setPasswordHash(encoder.encode(initialPassword(settings.getAdminPassword(), "administrator '" + settings.getAdminUsername() + "'")));
            admin.setFullName(branch.getName() + " Administrator");
            admin.setRole(Role.BRANCH_ADMIN);
            admin.setBranch(branch);
            users.save(admin);
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!users.existsByRole(Role.MANAGER)) {
            AppUser u = new AppUser();
            u.setUsername(username);
            u.setPasswordHash(encoder.encode(initialPassword(password, "support login '" + username + "'")));
            u.setFullName("Sacco Manager");
            u.setRole(Role.MANAGER);
            users.save(u);
            log.warn("Created initial manager account '{}'. Change its password immediately.", username);
        }
        if (settings.isSingleBranch()) seedSingleBranch();
        accountService.seedDefaults();                       // one chart for all branches
        branches.findAll().forEach(productService::seedDefaults);

        // Transactions recorded before the ledgers existed get their ML and GL lines now (once each)
        List<LedgerEntry> pending = ledger.findWithoutMemberLedger();
        pending.forEach(postingService::record);
        // Older entries posted before entries remembered their product: take it from their member-ledger line
        ledger.findByProductIsNull().forEach(e ->
                memberLedger.findFirstByLedgerEntryId(e.getId()).ifPresent(l -> {
                    e.setProduct(l.getProduct());
                    ledger.save(e);
                }));
        regroupEarlierDeposits();
        // members added before member logins existed get theirs now (once each)
        memberRepository.findAll().forEach(memberLogins::ensureLogin);
        // loans paid out before the loan_schedule table existed get their installments now (once each)
        loanRepository.findAll().forEach(loanSchedules::ensureGenerated);
        // loans whose interest was booked in full at payout move to monthly interest (one correcting entry each, once)
        interestRuns.convertLegacyLoans();
        if (!pending.isEmpty()) log.info("Posted {} earlier transactions to the member and general ledgers", pending.size());
    }
}
