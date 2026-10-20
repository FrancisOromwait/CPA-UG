package com.estates.sacco.web;

import com.estates.sacco.domain.Account;
import com.estates.sacco.domain.AccountType;
import com.estates.sacco.domain.ProductCategory;
import com.estates.sacco.domain.ProductDef;
import com.estates.sacco.repo.AccountRepository;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.ProductDefRepository;
import com.estates.sacco.security.BranchScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Product definitions: product id, category, and the chart-of-accounts account each product posts to.
 * The product details (name) are taken from that account, not typed in.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductDefRepository products;
    private final AccountRepository accounts;
    private final BranchRepository branches;
    private final BranchScope scope;
    private final com.estates.sacco.repo.MemberLedgerRepository memberLedger;
    private final com.estates.sacco.repo.LedgerRepository ledger;
    private final com.estates.sacco.service.AuditService audit;

    public record ProductReq(@NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "may only contain letters, digits, '.', '_' and '-'") String productCode,
                             @NotNull ProductCategory category, @NotNull Long accountId, String remarks,
                             Long interestProductId, Long interestIncomeAccountId) {}

    public record ProductUpdate(@NotNull Long accountId, String remarks, Long interestProductId,
                                Long interestIncomeAccountId, Boolean active) {}

    public record ProductDto(Long id, Long branchId, String branchCode, String productCode, ProductCategory category,
                             Long accountId, String accountCode, String productDetails, String remarks,
                             Long interestProductId, String interestProduct, Long interestIncomeAccountId,
                             String interestIncomeAccount, boolean active) {
        static ProductDto of(ProductDef p) {
            Account g = p.getGlAccount(), i = p.getInterestIncomeAccount();
            ProductDef ip = p.getInterestProduct();
            return new ProductDto(p.getId(), p.getBranch().getId(), p.getBranch().getCode(), p.getProductCode(),
                    p.getCategory(), g.getId(), g.getCode(), p.getName(), p.getRemarks(),
                    ip == null ? null : ip.getId(), ip == null ? null : ip.getProductCode() + " " + ip.getName(),
                    i == null ? null : i.getId(), i == null ? null : i.getCode() + " " + i.getName(), p.isActive());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<ProductDto> list(@RequestParam(required = false) Long branchId) {
        return products.search(scope.readBranch(branchId)).stream().map(ProductDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ProductDto create(@Valid @RequestBody ProductReq req) {
        Long branchId = scope.writeBranch();
        String code = req.productCode().trim().toUpperCase();
        if (products.existsByBranchIdAndProductCode(branchId, code)) {
            throw new ApiException(HttpStatus.CONFLICT, "Product ID " + code + " already exists");
        }
        ProductDef p = new ProductDef();
        p.setBranch(branches.findById(branchId).orElseThrow());
        p.setProductCode(code);
        p.setCategory(req.category());
        apply(p, req.accountId(), req.remarks(), req.interestProductId(), req.interestIncomeAccountId());
        return ProductDto.of(products.save(p));
    }

    /** The product id and category are fixed once created. */
    @PutMapping("/{id}")
    @Transactional
    ProductDto update(@PathVariable Long id, @Valid @RequestBody ProductUpdate req) {
        ProductDef p = products.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Product not found"));
        scope.assertAccess(p.getBranch().getId());
        apply(p, req.accountId(), req.remarks(), req.interestProductId(), req.interestIncomeAccountId());
        if (req.active() != null) p.setActive(req.active());
        return ProductDto.of(products.save(p));
    }

    private void apply(ProductDef p, Long accountId, String remarks, Long interestProductId, Long interestIncomeAccountId) {
        Long branchId = p.getBranch().getId();
        AccountType expected = switch (p.getCategory()) {
            case SAVINGS, SAVINGS_INTEREST -> AccountType.LIABILITY;
            case SHARES -> AccountType.EQUITY;
            case MEMBERSHIP -> AccountType.INCOME;
            case LOAN, INTEREST_RECEIVABLE -> AccountType.ASSET;
        };
        Account a = account(accountId, branchId, expected, "Product details account");
        p.setGlAccount(a);
        p.setName(a.getName());          // product details come from the chart of accounts
        p.setRemarks(remarks == null || remarks.isBlank() ? null : remarks.trim());

        if (p.getCategory() == ProductCategory.LOAN) {
            ProductDef ip = null;
            if (interestProductId != null) {
                ip = products.findById(interestProductId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Interest product not found"));
                if (!ip.getBranch().getId().equals(branchId) || ip.getCategory() != ProductCategory.INTEREST_RECEIVABLE) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Interest product must be an interest receivable product of this branch");
                }
            }
            p.setInterestProduct(ip);
            p.setInterestIncomeAccount(interestIncomeAccountId == null ? null
                    : account(interestIncomeAccountId, branchId, AccountType.INCOME, "Interest income account"));
        } else {
            p.setInterestProduct(null);
            p.setInterestIncomeAccount(null);
        }
    }

    private Account account(Long id, Long branchId, AccountType expected, String what) {
        Account a = accounts.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, what + " not found"));
        if (a.getType() != expected) {
            throw new ApiException(HttpStatus.BAD_REQUEST, what + " must be a " + expected.name().toLowerCase() + " account in the chart of accounts");
        }
        if (!a.isActive()) throw new ApiException(HttpStatus.BAD_REQUEST, what + " is inactive");
        return a;
    }

    /** A product that has never been used can be deleted. Otherwise make it inactive. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        ProductDef p = products.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Product not found"));
        scope.assertAccess(p.getBranch().getId());
        if (memberLedger.existsByProductId(id) || ledger.existsByProductId(id) || products.existsByInterestProductId(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "This product is in use. Make it inactive instead.");
        }
        audit.log(p.getBranch(), "DELETE", "Product", p.getId(), p.getProductCode() + " " + p.getName());
        products.delete(p);
    }
}
