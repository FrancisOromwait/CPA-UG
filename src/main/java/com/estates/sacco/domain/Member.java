package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "members", uniqueConstraints = @UniqueConstraint(columnNames = {"branch_id", "memberNo"}))
@Getter @Setter @NoArgsConstructor
public class Member {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(nullable = false, length = 30)
    private String memberNo;

    @Column(nullable = false)
    private String fullName;

    private String phone;
    private String nationalId;

    /** Employer whose payroll deducts this member's contributions. */
    private String employer;
    private String payrollNo;

    private LocalDate joinedOn = LocalDate.now();
    private boolean active = true;
}
