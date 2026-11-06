package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A person named on the membership application form: a nominated beneficiary, a biological child, or the mother or father. */
@Entity
@Table(name = "member_relatives")
@Getter @Setter @NoArgsConstructor
public class MemberRelative {
    public static final String BENEFICIARY = "BENEFICIARY", CHILD = "CHILD", MOTHER = "MOTHER", FATHER = "FATHER";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    /** BENEFICIARY, CHILD, MOTHER or FATHER. */
    @Column(nullable = false, length = 12)
    private String kind;

    /** Its line on the form (1, 2, 3...). */
    private int position;

    private String name;
    private String relationship;
    private String contact;

    /** Mother and father: alive (true), deceased (false), not said (null). */
    private Boolean alive;
}
