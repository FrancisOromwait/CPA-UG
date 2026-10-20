package com.estates.sacco.repo;

import com.estates.sacco.domain.GeneralLedgerLine;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface GeneralLedgerRepository extends JpaRepository<GeneralLedgerLine, Long> {
    boolean existsByAccountId(Long accountId);
    void deleteByLedgerEntryId(Long ledgerEntryId);
    void deleteByJournalId(Long journalId);
    void deleteByDepositGroup(String depositGroup);
    List<GeneralLedgerLine> findByLedgerEntry_DepositGroupOrderByIdAsc(String depositGroup);
    List<GeneralLedgerLine> findByJournalIdOrderByIdAsc(Long journalId);
    List<GeneralLedgerLine> findByLedgerEntryIdOrderByIdAsc(Long ledgerEntryId);


    @Query("""
        select g from GeneralLedgerLine g
        where (:branchId is null or g.branch.id = :branchId)
          and (:accountId is null or g.account.id = :accountId)
          and (:accountCode is null or g.account.code = :accountCode)
          and g.postedOn >= :from and g.postedOn <= :to
        order by g.postedOn desc, g.id desc
        """)
    List<GeneralLedgerLine> search(Long branchId, Long accountId, String accountCode, LocalDate from, LocalDate to, Pageable page);

    /** The same lines, oldest first: an account ledger read from its first line down, like a ledger book. */
    @Query("""
        select g from GeneralLedgerLine g
        where (:branchId is null or g.branch.id = :branchId)
          and (:accountId is null or g.account.id = :accountId)
          and (:accountCode is null or g.account.code = :accountCode)
          and g.postedOn >= :from and g.postedOn <= :to
        order by g.postedOn asc, g.id asc
        """)
    List<GeneralLedgerLine> searchOldestFirst(Long branchId, Long accountId, String accountCode, LocalDate from, LocalDate to, Pageable page);

    /** Rows of [branchId, accountId, debit minus credit] of everything before a date: where a running balance starts. */
    @Query("""
        select g.branch.id, g.account.id, sum(g.debit) - sum(g.credit) from GeneralLedgerLine g
        where g.account.id in :accountIds and g.branch.id in :branchIds and g.postedOn < :before
        group by g.branch.id, g.account.id
        """)
    List<Object[]> balancesBefore(List<Long> accountIds, List<Long> branchIds, LocalDate before);

    /** Rows of [lineId, branchId, accountId, debit, credit] from one date to another, oldest first (no entities loaded). */
    @Query("""
        select g.id, g.branch.id, g.account.id, g.debit, g.credit from GeneralLedgerLine g
        where g.account.id in :accountIds and g.branch.id in :branchIds and g.postedOn >= :from and g.postedOn <= :to
        order by g.postedOn asc, g.id asc
        """)
    List<Object[]> movementsBetween(List<Long> accountIds, List<Long> branchIds, LocalDate from, LocalDate to);

    /** Rows of [accountId, sum(debit), sum(credit)] between two dates, for one branch or (null) all branches. */
    @Query("""
        select g.account.id, sum(g.debit), sum(g.credit) from GeneralLedgerLine g
        where (:branchId is null or g.branch.id = :branchId)
          and g.postedOn >= :from and g.postedOn <= :to
        group by g.account.id
        """)
    List<Object[]> totalsBetween(Long branchId, LocalDate from, LocalDate to);
}
