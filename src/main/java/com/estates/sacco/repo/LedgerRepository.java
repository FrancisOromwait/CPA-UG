package com.estates.sacco.repo;

import com.estates.sacco.domain.LedgerEntry;
import com.estates.sacco.domain.Source;
import com.estates.sacco.domain.TxType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByPayrollBatchId(Long payrollBatchId);
    List<LedgerEntry> findByDepositGroupOrderByIdAsc(String depositGroup);
    List<LedgerEntry> findByLoanIdOrderByIdAsc(Long loanId);
    boolean existsByMemberId(Long memberId);
    boolean existsByBranchIdAndReference(Long branchId, String reference);
    boolean existsByReceivingAccountId(Long accountId);
    boolean existsByProductId(Long productId);


    /** Start-up checks: with hundreds of thousands of migrated entries these must not load the whole table. */
    @Query("select e from LedgerEntry e where not exists (select 1 from MemberLedgerLine m where m.ledgerEntry = e) order by e.id")
    List<LedgerEntry> findWithoutMemberLedger();

    List<LedgerEntry> findByProductIsNull();

    List<LedgerEntry> findByBranchIdAndTypeAndReference(Long branchId, TxType type, String reference);

    @Query("""
        select e from LedgerEntry e
        where e.depositGroup is null and e.payrollBatch is null and e.source <> com.estates.sacco.domain.Source.PAYROLL
          and e.type in :types and e.createdAt is not null
        order by e.id
        """)
    List<LedgerEntry> findUngroupedDeposits(List<TxType> types);

    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.member.id = :memberId and e.type = :type")
    BigDecimal sumByMember(Long memberId, TxType type);

    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.loan.id = :loanId and e.type = :type")
    BigDecimal sumByLoan(Long loanId, TxType type);

    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.loan.id = :loanId and e.type = com.estates.sacco.domain.TxType.LOAN_REPAYMENT")
    BigDecimal repaidOnLoan(Long loanId);

    /** Rows of [TxType, BigDecimal] for one branch. */
    @Query("select e.type, sum(e.amount) from LedgerEntry e where e.branch.id = :branchId group by e.type")
    List<Object[]> totalsByType(Long branchId);

    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.branch.id = :branchId and e.source = :source and e.type in :types")
    BigDecimal sumBySource(Long branchId, Source source, List<TxType> types);

    /** Regular deposits: savings, share and loan-repayment receipts that did not come through payroll. */
    @Query("""
        select e from LedgerEntry e
        where (:branchId is null or e.branch.id = :branchId)
          and e.type in (com.estates.sacco.domain.TxType.SAVINGS_DEPOSIT, com.estates.sacco.domain.TxType.SHARE_PURCHASE,
                       com.estates.sacco.domain.TxType.LOAN_REPAYMENT)
          and e.source <> com.estates.sacco.domain.Source.PAYROLL
          and e.source <> com.estates.sacco.domain.Source.SYSTEM
          and e.postedOn >= :from and e.postedOn <= :to
        order by e.postedOn desc, e.id desc
        """)
    List<LedgerEntry> searchDeposits(Long branchId, LocalDate from, LocalDate to, Pageable page);

    @Query("""
        select e from LedgerEntry e
        where (:branchId is null or e.branch.id = :branchId)
          and (:memberId is null or e.member.id = :memberId)
          and (:type is null or e.type = :type)
          and e.postedOn >= :from and e.postedOn <= :to
        order by e.postedOn desc, e.id desc
        """)
    List<LedgerEntry> search(Long branchId, Long memberId, TxType type, LocalDate from, LocalDate to, Pageable page);
}
