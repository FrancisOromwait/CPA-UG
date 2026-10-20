package com.estates.sacco.web;

import com.estates.sacco.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Request and response shapes for the REST API. */
public final class Dtos {
    private Dtos() {}

    // ---- branches & users
    public record BranchReq(@NotBlank @Size(max = 20) String code, @NotBlank String name, String location) {}

    public record BranchDto(Long id, String code, String name, String location, boolean active) {
        public static BranchDto of(Branch b) {
            return new BranchDto(b.getId(), b.getCode(), b.getName(), b.getLocation(), b.isActive());
        }
    }

    public record UserReq(@NotBlank String username, @NotBlank @Size(min = 8) String password,
                          @NotBlank String fullName, @NotNull Role role, Long branchId) {}

    public record UserDto(Long id, String username, String fullName, Role role, Long branchId, String branchCode,
                          String branchName, boolean enabled) {
        public static UserDto of(AppUser u) {
            Branch b = u.getBranch();
            return new UserDto(u.getId(), u.getUsername(), u.getFullName(), u.getRole(),
                    b == null ? null : b.getId(), b == null ? null : b.getCode(),
                    b == null ? null : b.getName(), u.isEnabled());
        }
    }

    // ---- members
    public record MemberReq(@NotBlank String memberNo, @NotBlank String fullName, String phone, String nationalId,
                            String employer, String payrollNo) {}

    public record MemberDto(Long id, Long branchId, String branchCode, String memberNo, String fullName, String phone,
                            String employer, String payrollNo, boolean active, String loginUsername) {
        public static MemberDto of(Member m) {
            return new MemberDto(m.getId(), m.getBranch().getId(), m.getBranch().getCode(), m.getMemberNo(),
                    m.getFullName(), m.getPhone(), m.getEmployer(), m.getPayrollNo(), m.isActive(), null);
        }

        /** The same member, with the username of their login (shown when they are added or their login is reset). */
        public MemberDto withLogin(String username) {
            return new MemberDto(id, branchId, branchCode, memberNo, fullName, phone, employer, payrollNo, active, username);
        }
    }

    // ---- savings & shares transactions
    public record TxReq(@NotNull Long memberId, @NotNull TxType type, @NotNull @Positive BigDecimal amount,
                        Source source, String reference, LocalDate date) {}

    public record EntryDto(Long id, Long branchId, String branchCode, Long memberId, String memberNo,
                           String memberName, Long loanId, TxType type, Source source, BigDecimal amount,
                           String reference, LocalDate postedOn) {
        public static EntryDto of(LedgerEntry e) {
            return new EntryDto(e.getId(), e.getBranch().getId(), e.getBranch().getCode(), e.getMember().getId(),
                    e.getMember().getMemberNo(), e.getMember().getFullName(),
                    e.getLoan() == null ? null : e.getLoan().getId(), e.getType(), e.getSource(), e.getAmount(),
                    e.getReference(), e.getPostedOn());
        }
    }

    // ---- loans
    public record LoanReq(@NotNull Long memberId, @NotNull @Positive BigDecimal principal,
                          @NotNull @PositiveOrZero BigDecimal annualRatePercent, @Min(1) @Max(600) int termMonths,
                          @PositiveOrZero BigDecimal applicationFee, LocalDate loanDate, InterestMethod interestMethod,
                          Long topUpOfLoanId, @PositiveOrZero BigDecimal processFee, @PositiveOrZero BigDecimal insuranceFee,
                          @PositiveOrZero BigDecimal legalCharges) {}

    /** {@code receivingAccountId}: the chart account the loan is paid out from (cash, bank or mobile money); without one the source decides. */
    public record DisburseReq(Source source, String reference, LocalDate date, Long receivingAccountId) {}

    public record RepayReq(@NotNull @Positive BigDecimal amount, Source source, String reference, LocalDate date) {}

    public record LoanDto(Long id, Long branchId, String branchCode, Long memberId, String memberName,
                          BigDecimal principal, BigDecimal annualRatePercent, int termMonths, BigDecimal totalDue,
                          BigDecimal applicationFee, BigDecimal repaid, BigDecimal outstanding, LoanStatus status, LocalDate appliedOn,
                          LocalDate disbursedOn, InterestMethod interestMethod, BigDecimal installment,
                          BigDecimal remaining, BigDecimal interestCharged, Long topUpOfLoanId, BigDecimal topUpSettled,
                          BigDecimal processFee, BigDecimal insuranceFee, BigDecimal legalCharges) {}

    // ---- payroll deductions
    public record PayrollLine(@NotNull Long memberId, @PositiveOrZero BigDecimal savings,
                              @PositiveOrZero BigDecimal shares, @PositiveOrZero BigDecimal loanRepayment,
                              Long loanId) {}

    public record PayrollReq(@NotBlank String employer,
                             @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be yyyy-MM") String period,
                             @NotNull Long receivingAccountId, LocalDate date,
                             @NotEmpty @Valid List<PayrollLine> lines) {}

    public record PayrollDto(Long id, Long branchId, String branchCode, String employer, String period,
                             LocalDate paymentDate, Long receivingAccountId, String receivingAccount,
                             BigDecimal totalAmount, int lineCount, String createdBy) {
        public static PayrollDto of(PayrollBatch p) {
            return new PayrollDto(p.getId(), p.getBranch().getId(), p.getBranch().getCode(), p.getEmployer(),
                    p.getPeriod(), p.getPaymentDate(),
                    p.getReceivingAccount() == null ? null : p.getReceivingAccount().getId(),
                    p.getReceivingAccount() == null ? null : p.getReceivingAccount().getCode() + " " + p.getReceivingAccount().getName(),
                    p.getTotalAmount(), p.getLineCount(), p.getCreatedBy());
        }
    }

    // ---- statements & reports
    public record Statement(MemberDto member, BigDecimal savingsBalance, BigDecimal sharesBalance,
                            List<LoanDto> loans, List<EntryDto> entries) {}

    public record BranchSummary(Long branchId, String branchCode, String branchName, long members,
                                BigDecimal savings, BigDecimal shares, BigDecimal loansDisbursed,
                                BigDecimal loanRepayments, BigDecimal loanOutstanding, long activeLoans,
                                BigDecimal payrollCollected) {}

    public record ConsolidatedReport(List<BranchSummary> branches, BranchSummary totals) {}
}
