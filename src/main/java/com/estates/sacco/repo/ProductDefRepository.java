package com.estates.sacco.repo;

import com.estates.sacco.domain.ProductCategory;
import com.estates.sacco.domain.ProductDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProductDefRepository extends JpaRepository<ProductDef, Long> {
    boolean existsByBranchIdAndProductCode(Long branchId, String productCode);
    boolean existsByGlAccountId(Long accountId);
    boolean existsByInterestIncomeAccountId(Long accountId);
    boolean existsByInterestProductId(Long productId);
    Optional<ProductDef> findByBranchIdAndProductCode(Long branchId, String productCode);

    /** The default product of a category: the oldest active one. */
    Optional<ProductDef> findFirstByBranchIdAndCategoryAndActiveTrueOrderByIdAsc(Long branchId, ProductCategory category);

    @Query("select p from ProductDef p where (:branchId is null or p.branch.id = :branchId) order by p.branch.id, p.productCode")
    List<ProductDef> search(Long branchId);
}
