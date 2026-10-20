package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Product definition: a product id and category, linked to the chart-of-accounts account that is posted
 * to in the general ledger. The product details (name) come from that account.
 */
@Entity
@Table(name = "product_def", uniqueConstraints = @UniqueConstraint(columnNames = {"branch_id", "productCode"}))
@Getter @Setter @NoArgsConstructor
public class ProductDef {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** The product id, e.g. COMSV. */
    @Column(nullable = false, length = 20)
    private String productCode;

    /** Product details: copied from the chart-of-accounts account name whenever the product is saved. */
    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductCategory category;

    /** The chart-of-accounts account this product posts to (savings: liability, shares: equity, loan and interest receivable: asset). */
    @ManyToOne(optional = false)
    @JoinColumn(name = "gl_account_id")
    private Account glAccount;

    private String remarks;

    /** Loan products only: the interest receivable product that holds this loan's interest. */
    @ManyToOne
    @JoinColumn(name = "interest_product_id")
    private ProductDef interestProduct;

    /** Loan products only: where interest is recognised as income. */
    @ManyToOne
    @JoinColumn(name = "interest_income_account_id")
    private Account interestIncomeAccount;

    private boolean active = true;
}
