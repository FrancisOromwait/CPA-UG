package com.estates.sacco.service;

import com.estates.sacco.domain.Account;
import com.estates.sacco.domain.AccountType;
import com.estates.sacco.domain.Branch;
import com.estates.sacco.repo.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.estates.sacco.domain.AccountType.*;

@Service
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accounts;

    private record Def(String code, String name, AccountType type) {}

    /** Standard Sacco chart: 1xxx assets, 2xxx liabilities, 3xxx equity, 4xxx income, 5xxx expenses. */
    private static final List<Def> DEFAULTS = List.of(
            new Def("1100", "Cash on Hand", ASSET),
            new Def("1200", "Bank Accounts", ASSET),
            new Def("1300", "Mobile Money", ASSET),
            new Def("1400", "Loans to Members", ASSET),
            new Def("1500", "Interest Receivable", ASSET),
            new Def("1600", "Payroll Deductions Receivable", ASSET),
            new Def("2100", "Member Savings Deposits", LIABILITY),
            new Def("2200", "Accounts Payable", LIABILITY),
            new Def("2300", "Dividends Payable", LIABILITY),
            new Def("3100", "Member Share Capital", EQUITY),
            new Def("3200", "Retained Earnings", EQUITY),
            new Def("4100", "Loan Interest Income", INCOME),
            new Def("4200", "Loan Processing Fees", INCOME),
            new Def("4300", "Other Income", INCOME),
            new Def("4400", "Savings Withdrawal Fees", INCOME),
            new Def("5100", "Salaries and Wages", EXPENSE),
            new Def("5200", "Rent and Utilities", EXPENSE),
            new Def("5300", "Office Expenses", EXPENSE),
            new Def("5400", "Interest on Savings", EXPENSE),
            new Def("5500", "Bad Debts Expense", EXPENSE));

    private final AccountCodes codes;

    /** A chart of accounts kept as a file on the classpath (one account per line: code|name|type|parent code|system Y/N); blank: the standard chart. */
    @Value("${sacco.accounts.chart-file:}")
    private String chartFile;

    /** Creates the one chart, shared by every branch, when there is none yet. */
    @Transactional
    public void seedDefaults() {
        if (accounts.count() > 0) {          // a chart made before withdrawal fees existed gets the fee account
            if (accounts.findByCode(codes.getWithdrawalFeeIncome()).isEmpty()) {
                Account fees = new Account();
                fees.setCode(codes.getWithdrawalFeeIncome());
                fees.setName("Savings Withdrawal Fees");
                fees.setType(INCOME);
                fees.setSystemAccount(true);
                accounts.save(fees);
            }
            return;
        }
        if (!chartFile.isBlank()) {
            loadChartFile();
            return;
        }
        for (Def d : DEFAULTS) {
            Account a = new Account();
            a.setCode(d.code());
            a.setName(d.name());
            a.setType(d.type());
            a.setSystemAccount(true);
            accounts.save(a);
        }
    }

    private void loadChartFile() {
        java.util.Map<String, String> parents = new java.util.LinkedHashMap<>();
        try (var in = new org.springframework.core.io.ClassPathResource(chartFile).getInputStream();
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\\|", -1);
                Account a = new Account();
                a.setCode(f[0].trim());
                a.setName(f[1].trim());
                a.setType(AccountType.valueOf(f[2].trim()));
                a.setSystemAccount("Y".equals(f[4].trim()));
                accounts.save(a);
                if (!f[3].isBlank()) parents.put(a.getCode(), f[3].trim());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read the chart of accounts " + chartFile, e);
        }
        parents.forEach((code, parent) -> accounts.findByCode(code).ifPresent(a -> {
            accounts.findByCode(parent).ifPresent(a::setParent);
            accounts.save(a);
        }));
    }
}
