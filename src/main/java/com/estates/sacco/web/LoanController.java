package com.estates.sacco.web;

import com.estates.sacco.domain.Loan;
import com.estates.sacco.domain.LoanStatus;
import com.estates.sacco.domain.Source;
import com.estates.sacco.domain.TxType;
import com.estates.sacco.repo.LoanRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.LedgerService;
import com.estates.sacco.web.Dtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
public class LoanController {
    private final LoanRepository loans;
    private final LedgerService ledgerService;
    private final BranchScope scope;
    private final com.estates.sacco.service.EntryAdminService admin;
    private final com.estates.sacco.repo.LedgerRepository ledger;
    private final com.estates.sacco.service.LoanScheduleService schedules;
    private final com.estates.sacco.service.PdfReportService pdf;
    private final com.estates.sacco.service.DeploymentSettings settings;
    private final com.estates.sacco.repo.AccountRepository accounts;
    private final com.estates.sacco.repo.MemberLedgerRepository memberLedgerLines;
    private final com.estates.sacco.repo.GeneralLedgerRepository generalLedgerLines;
    private final com.estates.sacco.service.AccountCodes codes;

    @GetMapping
    @Transactional(readOnly = true)
    List<LoanDto> list(@RequestParam(required = false) Long branchId, @RequestParam(required = false) LoanStatus status) {
        return loans.search(scope.readBranch(branchId), status).stream().map(ledgerService::loanDto).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    LoanDto get(@PathVariable Long id) {
        return ledgerService.loanDto(ledgerService.requireLoan(id));
    }

    /** Record a loan application (status PENDING). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    LoanDto apply(@Valid @RequestBody LoanReq req) {
        var member = ledgerService.requireMember(req.memberId());
        Loan l = ledgerService.apply(member, req.principal(), req.annualRatePercent(), req.termMonths(),
                new com.estates.sacco.service.LoanFees(req.applicationFee(), req.processFee(), req.insuranceFee(), req.legalCharges()),
                req.loanDate(), req.interestMethod(), req.topUpOfLoanId());
        return ledgerService.loanDto(l);
    }

    /** What the loan form starts with: the application fee, the process fee and insurance as a percentage of the loan, and legal charges. */
    public record FeeDefaults(java.math.BigDecimal applicationFee, java.math.BigDecimal processPercent,
                              java.math.BigDecimal insurancePercent, java.math.BigDecimal legalCharges, boolean dailyInterest,
                              java.math.BigDecimal defaultRate) {}

    @GetMapping("/fee-defaults")
    FeeDefaults feeDefaults() {
        return new FeeDefaults(settings.getLoanApplicationFee(), settings.getLoanProcessPercent(), settings.getLoanInsurancePercent(),
                settings.getLoanLegalCharges(), settings.isLoanInterestDaily(), settings.getLoanDefaultRate());
    }

    @PostMapping("/{id}/reject")
    @Transactional
    LoanDto reject(@PathVariable Long id) {
        Loan l = ledgerService.requireLoan(id);
        if (l.getStatus() != LoanStatus.PENDING) throw new ApiException(HttpStatus.CONFLICT, "Only a pending loan can be rejected");
        l.setStatus(LoanStatus.REJECTED);
        return ledgerService.loanDto(loans.save(l));
    }

    /** Approve and pay out a pending loan. */
    @PostMapping("/{id}/disburse")
    @Transactional
    LoanDto disburse(@PathVariable Long id, @RequestBody(required = false) DisburseReq req) {
        Loan l = ledgerService.requireLoan(id);
        DisburseReq r = req == null ? new DisburseReq(null, null, null, null) : req;
        Source source = r.source();
        com.estates.sacco.domain.Account paidFrom = null;
        if (r.receivingAccountId() != null) {
            paidFrom = accounts.findById(r.receivingAccountId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
            if (paidFrom.getType() != com.estates.sacco.domain.AccountType.ASSET || !paidFrom.isActive()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The loan must be paid out from an active asset account (cash, bank or mobile money)");
            }
            source = codes.sourceOf(paidFrom);
        }
        return ledgerService.loanDto(ledgerService.disburse(l, source, r.reference(), r.date(), paidFrom));
    }

    /** Cash/bank/mobile repayment. Payroll repayments go through /api/payroll. */
    @PostMapping("/{id}/repayments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    EntryDto repay(@PathVariable Long id, @Valid @RequestBody RepayReq req) {
        if (req.source() == Source.PAYROLL) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Payroll deductions are posted through /api/payroll");
        }
        Loan l = ledgerService.requireLoan(id);
        return EntryDto.of(ledgerService.post(l.getMember(), TxType.LOAN_REPAYMENT, req.amount(), req.source(),
                req.date(), req.reference(), l, null));
    }

    public record Preview(java.math.BigDecimal installment, java.math.BigDecimal totalInterest, java.math.BigDecimal totalDue) {}

    /** What a loan would cost, for the form, before anything is saved. Reducing balance unless told otherwise. */
    @GetMapping("/preview")
    Preview preview(@RequestParam java.math.BigDecimal principal, @RequestParam java.math.BigDecimal annualRatePercent,
                    @RequestParam int termMonths, @RequestParam(required = false) com.estates.sacco.domain.InterestMethod method) {
        if (principal.signum() <= 0 || annualRatePercent.signum() < 0 || termMonths < 1 || termMonths > 600) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter an amount, a rate and a term of 1 to 600 months");
        }
        var m = method == null ? com.estates.sacco.domain.InterestMethod.REDUCING_BALANCE : method;
        java.math.BigDecimal total = com.estates.sacco.service.LoanScheduleService.totalDue(m, principal, annualRatePercent, termMonths);
        return new Preview(com.estates.sacco.service.LoanScheduleService.monthlyInstallment(m, principal, annualRatePercent, termMonths),
                total.subtract(principal.setScale(2, java.math.RoundingMode.HALF_UP)), total);
    }

    /** The monthly repayment schedule of a loan, with what has been paid against it. */
    @GetMapping("/{id}/schedule")
    @Transactional(readOnly = true)
    com.estates.sacco.service.ReportDoc schedule(@PathVariable Long id) {
        return schedules.document(ledgerService.requireLoan(id));
    }

    @GetMapping("/{id}/schedule/pdf")
    @Transactional(readOnly = true)
    org.springframework.http.ResponseEntity<byte[]> schedulePdf(@PathVariable Long id) {
        byte[] bytes = pdf.render(schedules.document(ledgerService.requireLoan(id)), scope.me().getUsername());
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.inline().filename("loan-" + id + "-schedule.pdf").build().toString())
                .body(bytes);
    }

    public record LoanEdit(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive java.math.BigDecimal principal,
                           @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero java.math.BigDecimal annualRatePercent,
                           @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(600) int termMonths,
                           @jakarta.validation.constraints.PositiveOrZero java.math.BigDecimal applicationFee, java.time.LocalDate loanDate,
                           @jakarta.validation.constraints.PositiveOrZero java.math.BigDecimal processFee,
                           @jakarta.validation.constraints.PositiveOrZero java.math.BigDecimal insuranceFee,
                           @jakarta.validation.constraints.PositiveOrZero java.math.BigDecimal legalCharges) {}

    /** A general ledger line of the loan, with the date it was posted. */
    public record GlOfLoan(java.time.LocalDate date, String accountCode, String accountName, String description,
                           java.math.BigDecimal debit, java.math.BigDecimal credit) {}

    public record LoanView(LoanDto loan, java.util.List<DepositController.DepositDto> entries,
                           java.util.List<EntryController.MlView> memberLedger, java.util.List<GlOfLoan> generalLedger) {}

    /** The loan with its payout and repayments, and the member ledger and general ledger lines they made. */
    @GetMapping("/{id}/view")
    @Transactional(readOnly = true)
    LoanView viewLoan(@PathVariable Long id) {
        Loan l = ledgerService.requireLoan(id);
        var entries = ledger.findByLoanIdOrderByIdAsc(id);
        java.util.List<EntryController.MlView> ml = new java.util.ArrayList<>();
        java.util.List<GlOfLoan> gl = new java.util.ArrayList<>();
        for (var e : entries) {
            memberLedgerLines.findByLedgerEntryIdOrderByIdAsc(e.getId()).forEach(m ->
                    ml.add(new EntryController.MlView(m.getPostedOn(), m.getBranchCode(), m.getProductCode(), m.getDescription(), m.getDebit(), m.getCredit())));
            generalLedgerLines.findByLedgerEntryIdOrderByIdAsc(e.getId()).forEach(g ->
                    gl.add(new GlOfLoan(g.getPostedOn(), g.getAccount().getCode(), g.getAccount().getName(), g.getDescription(), g.getDebit(), g.getCredit())));
        }
        return new LoanView(ledgerService.loanDto(l), entries.stream().map(DepositController.DepositDto::of).toList(), ml, gl);
    }

    @PutMapping("/{id}")
    @Transactional
    LoanDto editLoan(@PathVariable Long id, @Valid @RequestBody LoanEdit req) {
        Loan l = ledgerService.requireLoan(id);
        return ledgerService.loanDto(admin.editLoan(l, req.principal(), req.annualRatePercent(), req.termMonths(),
                new com.estates.sacco.service.LoanFees(req.applicationFee(), req.processFee(), req.insuranceFee(), req.legalCharges()), req.loanDate()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteLoan(@PathVariable Long id) {
        admin.deleteLoan(ledgerService.requireLoan(id));
    }
}
