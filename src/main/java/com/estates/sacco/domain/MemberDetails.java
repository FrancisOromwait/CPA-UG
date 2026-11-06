package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * What the CPA SACCO membership application form asks for beyond the member record itself (name, telephone, NIN and place of work
 * stay on {@link Member}). One row per member, made when the details are first saved. The beneficiaries, children and parents are
 * {@link MemberRelative}s.
 */
@Entity
@Table(name = "member_details")
@Getter @Setter @NoArgsConstructor
public class MemberDetails {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "member_id", unique = true)
    private Member member;

    /** FM number, for a member of ICPAU. */
    @Column(length = 40) private String fmNumber;
    /** ICPAU ID, for one not yet an ICPAU member. */
    @Column(length = 40) private String icpauId;
    private String address;
    private String email;
    private LocalDate dateOfBirth;
    @Column(length = 40) private String passportNo;
    @Column(length = 40) private String tin;
    /** SINGLE, MARRIED or OTHER. */
    @Column(length = 10) private String maritalStatus;
    private String spouseName;
    @Column(length = 60) private String spouseTel;
    /** Job position. */
    private String designation;
    /** Next of kin, name and telephone, as one line on the form. */
    private String nextOfKin;
    @Column(length = 1000) private String remarks;
    /** The date the applicant signed. */
    private LocalDate applicationDate;

    // official use
    private String checkedBy;
    private String checkedByDesignation;
    private LocalDate checkedOn;
    private String approvedBy;
    private String approvedByDesignation;
}
