package com.estates.sacco.service;

import com.estates.sacco.domain.Account;
import com.estates.sacco.domain.Branch;
import com.estates.sacco.domain.ProductCategory;
import com.estates.sacco.domain.ProductDef;
import com.estates.sacco.repo.AccountRepository;
import com.estates.sacco.repo.ProductDefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {
    private final ProductDefRepository products;
    private final AccountRepository accounts;
    private final AccountCodes codes;

    /**
     * Makes sure a branch has the four basic products the posting engine falls back on
     * (savings, shares, loan, loan interest receivable). Safe to run repeatedly.
     */
    @Transactional
    public void seedDefaults(Branch branch) {
        seed(branch, "SAV01", ProductCategory.SAVINGS, codes.getSavings());
        seed(branch, "SHR01", ProductCategory.SHARES, codes.getShares());
        if (!codes.getMembership().isBlank()) seed(branch, "MEM01", ProductCategory.MEMBERSHIP, codes.getMembership());
        if (!codes.getSavingsInterestPayable().isBlank()) seed(branch, "SIN01", ProductCategory.SAVINGS_INTEREST, codes.getSavingsInterestPayable());
        ProductDef interest = seed(branch, "LIN01", ProductCategory.INTEREST_RECEIVABLE, codes.getInterestReceivable());
        ProductDef loan = seed(branch, "LON01", ProductCategory.LOAN, codes.getLoans());
        if (loan != null) {
            if (loan.getInterestProduct() == null && interest != null) loan.setInterestProduct(interest);
            if (loan.getInterestIncomeAccount() == null) {
                loan.setInterestIncomeAccount(accounts.findByCode(codes.getLoanInterestIncome()).orElse(null));
            }
            products.save(loan);
        }
    }

    private ProductDef seed(Branch b, String code, ProductCategory cat, String accountCode) {
        ProductDef existing = products.findByBranchIdAndProductCode(b.getId(), code).orElse(null);
        if (existing != null) return existing;
        Account account = accounts.findByCode(accountCode).orElse(null);
        if (account == null) {
            log.warn("Branch {} has no account {}; product {} not created", b.getCode(), accountCode, code);
            return null;
        }
        ProductDef p = new ProductDef();
        p.setBranch(b);
        p.setProductCode(code);
        p.setName(account.getName());
        p.setCategory(cat);
        p.setGlAccount(account);
        return products.save(p);
    }
}
