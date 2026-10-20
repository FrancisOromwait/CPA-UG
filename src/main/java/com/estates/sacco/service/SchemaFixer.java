package com.estates.sacco.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hibernate adds columns but never drops constraints. The first version of product_def allowed only one
 * product per category; this removes that old unique index so a branch can have many products per category.
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class SchemaFixer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    private static final String KEEP = "(select code, min(id) keep_id from accounts group by code)";

    /**
     * Older databases kept a separate copy of the chart for every branch. Merge the copies into one:
     * everything that pointed at a duplicate account is pointed at the oldest account with that code,
     * the duplicates are removed, and the branch column goes. Safe to run again (it does nothing once merged).
     */
    private void mergeBranchCharts() {
        try {
            Integer branchColumn = jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = database() "
                    + "and table_name = 'accounts' and column_name = 'branch_id'", Integer.class);
            if (branchColumn != null && branchColumn > 0) {
                int moved = 0;
                // the old, unused interest-receivable account column on products must not hold on to a duplicate
                Integer legacy = jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = database() "
                        + "and table_name = 'product_def' and column_name = 'interest_receivable_account_id'", Integer.class);
                if (legacy != null && legacy > 0) jdbc.update("update product_def set interest_receivable_account_id = null");
                moved += jdbc.update("update general_ledger t join accounts a on a.id = t.account_id join " + KEEP + " k on k.code = a.code "
                        + "set t.account_id = k.keep_id where t.account_id <> k.keep_id");
                moved += jdbc.update("update product_def t join accounts a on a.id = t.gl_account_id join " + KEEP + " k on k.code = a.code "
                        + "set t.gl_account_id = k.keep_id where t.gl_account_id <> k.keep_id");
                moved += jdbc.update("update product_def t join accounts a on a.id = t.interest_income_account_id join " + KEEP + " k on k.code = a.code "
                        + "set t.interest_income_account_id = k.keep_id where t.interest_income_account_id <> k.keep_id");
                moved += jdbc.update("update ledger_entries t join accounts a on a.id = t.receiving_account_id join " + KEEP + " k on k.code = a.code "
                        + "set t.receiving_account_id = k.keep_id where t.receiving_account_id <> k.keep_id");
                moved += jdbc.update("update payroll_batches t join accounts a on a.id = t.receiving_account_id join " + KEEP + " k on k.code = a.code "
                        + "set t.receiving_account_id = k.keep_id where t.receiving_account_id <> k.keep_id");
                moved += jdbc.update("update accounts c join accounts p on p.id = c.parent_id join " + KEEP + " k on k.code = p.code "
                        + "set c.parent_id = k.keep_id where c.parent_id <> k.keep_id");
                int removed = jdbc.update("delete a from accounts a join " + KEEP + " k on k.code = a.code where a.id <> k.keep_id");

                for (String fk : jdbc.queryForList("select constraint_name from information_schema.key_column_usage where table_schema = database() "
                        + "and table_name = 'accounts' and column_name = 'branch_id' and referenced_table_name is not null", String.class)) {
                    jdbc.execute("alter table accounts drop foreign key `" + fk + "`");
                }
                for (String idx : jdbc.queryForList("select index_name from information_schema.statistics where table_schema = database() "
                        + "and table_name = 'accounts' and index_name <> 'PRIMARY' group by index_name "
                        + "having sum(column_name = 'branch_id') > 0", String.class)) {
                    jdbc.execute("alter table accounts drop index `" + idx + "`");
                }
                jdbc.execute("alter table accounts drop column branch_id");
                log.info("Chart of accounts merged into one shared chart: {} references repointed, {} duplicate accounts removed", moved, removed);
            }
            Integer uniqueCode = jdbc.queryForObject("select count(*) from (select index_name from information_schema.statistics "
                    + "where table_schema = database() and table_name = 'accounts' and non_unique = 0 and index_name <> 'PRIMARY' "
                    + "group by index_name having count(*) = 1 and max(column_name = 'code') = 1) u", Integer.class);
            if (uniqueCode != null && uniqueCode == 0) {
                jdbc.execute("alter table accounts add unique key uk_accounts_code (code)");
            }
        } catch (Exception e) {
            log.debug("Shared chart merge not needed or not supported here: {}", e.getMessage());
        }
    }

    /** Interest runs started as one line per installment; they are now one line per loan per month (table interest_charges). */
    private void dropOldInterestStructures() {
        try {
            Integer old = jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = database() and table_name = 'interest_accruals'", Integer.class);
            if (old != null && old > 0) {
                Integer rows = jdbc.queryForObject("select count(*) from interest_accruals", Integer.class);
                if (rows != null && rows == 0) {
                    jdbc.execute("drop table interest_accruals");
                    log.info("Dropped the unused interest_accruals table (replaced by interest_charges)");
                }
            }
        } catch (Exception e) {
            log.debug("Old interest table cleanup skipped: {}", e.getMessage());
        }
        try {
            // a month can now be computed again for loans it missed, so a branch can have several runs for one month
            for (String idx : jdbc.queryForList("select index_name from information_schema.statistics where table_schema = database() "
                    + "and table_name = 'interest_runs' and non_unique = 0 and index_name <> 'PRIMARY' group by index_name", String.class)) {
                jdbc.execute("alter table interest_runs drop index `" + idx + "`");
                log.info("Dropped the one-run-per-month index {} on interest_runs", idx);
            }
        } catch (Exception e) {
            log.debug("Interest run index cleanup skipped: {}", e.getMessage());
        }
    }

    /**
     * The loan application fee is taken out of what is paid out (one payout, bank credited with the net amount, fee to income).
     * Earlier the fee was booked as a separate entry; remove those, and clear the ledger lines of payouts that do not carry the
     * fee yet so the startup posting step re-posts them correctly. Safe to run again: it only touches what is not yet correct.
     */
    private void feeKeptOutOfPayout() {
        try {
            jdbc.update("delete from general_ledger where ledger_entry_id in (select id from ledger_entries where type = 'LOAN_FEE')");
            jdbc.update("delete from member_ledger where ledger_entry_id in (select id from ledger_entries where type = 'LOAN_FEE')");
            int gone = jdbc.update("delete from ledger_entries where type = 'LOAN_FEE'");
            String needsFee = """
                from ledger_entries le join loans l on l.id = le.loan_id
                where le.type = 'LOAN_DISBURSEMENT' and l.application_fee > 0
                  and not exists (select 1 from general_ledger g join accounts a on a.id = g.account_id
                                  where g.ledger_entry_id = le.id and a.code = '4200')
                """;
            List<Long> payouts = jdbc.queryForList("select le.id " + needsFee, Long.class);
            for (Long id : payouts) {
                jdbc.update("delete from member_ledger where ledger_entry_id = ?", id);
                jdbc.update("delete from general_ledger where ledger_entry_id = ?", id);
            }
            if (gone > 0 || !payouts.isEmpty()) log.info("Application fees now come out of the payout: removed {} separate fee entries, re-posting {} payouts", gone, payouts.size());
        } catch (Exception e) {
            log.debug("Application fee migration skipped: {}", e.getMessage());
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> indexes = jdbc.queryForList("""
                select index_name from information_schema.statistics
                where table_schema = database() and table_name = 'product_def'
                  and non_unique = 0 and index_name <> 'PRIMARY'
                group by index_name
                having group_concat(column_name order by seq_in_index separator ',') = 'branch_id,category'
                """, String.class);
            for (String index : indexes) {
                jdbc.execute("alter table product_def drop index `" + index + "`");
                log.info("Dropped old one-product-per-category index {}", index);
            }
        } catch (Exception e) {
            log.debug("Schema fix not needed or not supported here: {}", e.getMessage());
        }
        try {
            List<String> notNull = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = database() and table_name = 'general_ledger'
                  and column_name = 'ledger_entry_id' and is_nullable = 'NO'
                """, String.class);
            if (!notNull.isEmpty()) {
                jdbc.execute("alter table general_ledger modify ledger_entry_id bigint null");
                log.info("general_ledger.ledger_entry_id now allows journal lines (no transaction)");
            }
        } catch (Exception e) {
            log.debug("Schema fix not needed or not supported here: {}", e.getMessage());
        }
        try {   // the transaction type column was created as a fixed list; new types need it to be plain text
            List<String> fixed = jdbc.queryForList("""
                select column_type from information_schema.columns
                where table_schema = database() and table_name = 'ledger_entries' and column_name = 'type'
                  and data_type = 'enum'
                """, String.class);
            if (!fixed.isEmpty()) {
                jdbc.execute("alter table ledger_entries modify `type` varchar(25) not null");
                log.info("ledger_entries.type is now plain text, so new transaction types can be added");
            }
        } catch (Exception e) {
            log.debug("Schema fix not needed or not supported here: {}", e.getMessage());
        }
        feeKeptOutOfPayout();
        try {   // the user role column was a fixed list; new roles need plain text
            List<String> fixed = jdbc.queryForList("""
                select column_type from information_schema.columns
                where table_schema = database() and table_name = 'app_users' and column_name = 'role' and data_type = 'enum'
                """, String.class);
            if (!fixed.isEmpty()) {
                jdbc.execute("alter table app_users modify `role` varchar(20) not null");
                log.info("app_users.role is now plain text, so new roles can be added");
            }
        } catch (Exception e) {
            log.debug("Schema fix not needed or not supported here: {}", e.getMessage());
        }
        mergeBranchCharts();
        dropOldInterestStructures();
        for (String table : List.of("general_ledger", "member_ledger")) {
            try {
                int n = jdbc.update("update " + table + " set branch_code = (select code from branches where branches.id = " + table
                        + ".branch_id) where branch_code is null");
                if (n > 0) log.info("Filled in branch_code on {} existing {} rows", n, table);
            } catch (Exception e) {
                log.debug("Branch code backfill skipped for {}: {}", table, e.getMessage());
            }
        }
    }
}
