package com.estates.sacco.web;

import com.estates.sacco.domain.Branch;
import com.estates.sacco.domain.Member;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.LoanRepository;
import com.estates.sacco.repo.MemberRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.LedgerService;
import com.estates.sacco.web.Dtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {
    private final MemberRepository members;
    private final BranchRepository branches;
    private final LedgerRepository ledger;
    private final LoanRepository loans;
    private final LedgerService ledgerService;
    private final BranchScope scope;
    private final com.estates.sacco.service.AuditService audit;
    private final com.estates.sacco.service.MemberLoginService logins;

    @GetMapping
    @Transactional(readOnly = true)
    List<MemberDto> list(@RequestParam(required = false) Long branchId, @RequestParam(required = false) String q) {
        String pattern = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        return members.search(scope.readBranch(branchId), pattern).stream().map(MemberDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    MemberDto create(@Valid @RequestBody MemberReq req) {
        Long branchId = scope.writeBranch();
        String no = req.memberNo().trim();
        if (members.existsByBranchIdAndMemberNo(branchId, no)) {
            throw new ApiException(HttpStatus.CONFLICT, "Member number " + no + " already exists in this branch");
        }
        Member m = new Member();
        m.setBranch(branches.findById(branchId).orElseThrow());
        m.setMemberNo(no);
        m.setFullName(req.fullName().trim());
        m.setPhone(req.phone());
        m.setNationalId(req.nationalId());
        m.setEmployer(req.employer());
        m.setPayrollNo(req.payrollNo());
        Member saved = members.save(m);
        String login = logins.ensureLogin(saved).getUsername();      // the member's own login: username and password are the full names
        audit.log(saved.getBranch(), "CREATE", "Member login", saved.getId(), "Login " + login + " for " + saved.getMemberNo());
        return MemberDto.of(members.findById(saved.getId()).orElseThrow()).withLogin(login);
    }

    /** Savings, shares, loans and full transaction history for one member. */
    @GetMapping("/{id}/statement")
    @Transactional(readOnly = true)
    Statement statement(@PathVariable Long id) {
        Member m = ledgerService.requireMember(id);
        List<LoanDto> memberLoans = loans.findByMemberId(id).stream().map(ledgerService::loanDto).toList();
        List<EntryDto> entries = ledger.search(null, id, null, LocalDate.of(2000, 1, 1), LocalDate.of(2999, 12, 31),
                PageRequest.of(0, 1000)).stream().map(EntryDto::of).toList();
        return new Statement(MemberDto.of(m), ledgerService.savingsBalance(id), ledgerService.sharesBalance(id),
                memberLoans, entries);
    }

    @PutMapping("/{id}")
    @Transactional
    MemberDto edit(@PathVariable Long id, @Valid @RequestBody MemberReq req) {
        Member m = ledgerService.requireMember(id);
        String no = req.memberNo().trim();
        if (!no.equals(m.getMemberNo()) && members.existsByBranchIdAndMemberNo(m.getBranch().getId(), no)) {
            throw new ApiException(HttpStatus.CONFLICT, "Member number " + no + " already exists in this branch");
        }
        String before = m.getMemberNo() + " " + m.getFullName();
        String oldName = m.getFullName();
        m.setMemberNo(no);
        m.setFullName(req.fullName().trim());
        m.setPhone(req.phone());
        m.setNationalId(req.nationalId());
        m.setEmployer(req.employer());
        m.setPayrollNo(req.payrollNo());
        logins.renamed(m, oldName);
        audit.log(m.getBranch(), "EDIT", "Member", m.getId(), "Was: " + before + " | Now: " + m.getMemberNo() + " " + m.getFullName());
        return MemberDto.of(members.save(m));
    }

    /** A member with any transactions or loans cannot be deleted; their history must stay. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        Member m = ledgerService.requireMember(id);
        if (ledger.existsByMemberId(id) || !loans.findByMemberId(id).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "This member has transactions or loans, so it cannot be deleted. Their history must stay.");
        }
        audit.log(m.getBranch(), "DELETE", "Member", m.getId(), m.getMemberNo() + " " + m.getFullName());
        logins.remove(m);
        members.delete(m);
    }

    /** Gives the member their first password back (their full names) and switches their login on. */
    @PostMapping("/{id}/reset-login")
    @Transactional
    MemberDto resetLogin(@PathVariable Long id) {
        Member m = ledgerService.requireMember(id);
        String login = logins.reset(m);
        audit.log(m.getBranch(), "EDIT", "Member login", m.getId(), "Login " + login + " reset to the first password");
        return MemberDto.of(m).withLogin(login);
    }
}
