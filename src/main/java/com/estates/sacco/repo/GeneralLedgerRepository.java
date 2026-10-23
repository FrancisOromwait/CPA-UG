package com.estates.sacco.repo;

import com.estates.sacco.domain.GeneralLedgerLine;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
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

    /**
     * The cashbook: the debit lines (money in) or credit lines (money out) on the given accounts, oldest first, as rows of
     * [postedOn, accountId, amount, description, memberNo, memberName, entryId, entryReference, depositGroup, batchId,
     * batchEmployer, batchPeriod, journalId, journalNo, journalReference, narration] (no entities loaded).
     */
    @Query("""
        select g.postedOn, g.account.id, case when :out = true then g.credit else g.debit end, g.description, m.memberNo, m.fullName,
               le.id, le.reference, le.depositGroup, pb.id, pb.employer, pb.period,
               j.id, j.journalNo, j.reference, j.narration
        from GeneralLedgerLine g left join g.member m left join g.ledgerEntry le left join le.payrollBatch pb left join g.journal j
        where (:branchId is null or g.branch.id = :branchId) and g.account.id in :accountIds
          and ((:out = false and g.debit > 0) or (:out = true and g.credit > 0)) and g.postedOn >= :from and g.postedOn <= :to
        order by g.postedOn asc, g.id asc
        """)
    List<Object[]> cashbook(Long branchId, Collection<Long> accountIds, LocalDate from, LocalDate to, boolean out);

    /** Rows of [journalId, accountId]: the accounts on the other side of these journals (credited, or with {@code debited} debited). */
    @Query("""
        select distinct g.journal.id, g.account.id from GeneralLedgerLine g
        where g.journal.id in :ids and ((:debited = false and g.credit > 0) or (:debited = true and g.debit > 0))
        """)
    List<Object[]> otherSideOfJournals(Collection<Long> ids, boolean debited);

    /** Rows of [entryId, accountId]: the accounts on the other side of these transactions. */
    @Query("""
        select distinct g.ledgerEntry.id, g.account.id from GeneralLedgerLine g
        where g.ledgerEntry.id in :ids and ((:debited = false and g.credit > 0) or (:debited = true and g.debit > 0))
        """)
    List<Object[]> otherSideOfEntries(Collection<Long> ids, boolean debited);

    /** Rows of [memberId, interest]: the part of each member's loan repayments that paid interest, up to a date (the member summary). */
    @Query("""
        select le.member.id, sum(g.credit) from GeneralLedgerLine g join g.ledgerEntry le
        where le.type = com.estates.sacco.domain.TxType.LOAN_REPAYMENT and g.account.id in :interestAccounts and g.credit > 0
          and (:branchId is null or g.branch.id = :branchId) and le.postedOn <= :to
        group by le.member.id
        """)
    List<Object[]> repaymentInterestByMember(Long branchId, Collection<Long> interestAccounts, LocalDate to);

    /** Rows of [depositGroup, accountId]: the accounts credited by the parts of these regular deposits. */
    @Query("select distinct g.ledgerEntry.depositGroup, g.account.id from GeneralLedgerLine g where g.ledgerEntry.depositGroup in :groups and g.credit > 0")
    List<Object[]> creditedByDeposits(Collection<String> groups);
}
