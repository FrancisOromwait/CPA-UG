package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A member's photo, kept in the database (files on the server are lost when the app is deployed again). It sits apart from the
 * member record so lists of members never load pictures. Stored small: a JPEG of at most 400 pixels a side.
 */
@Entity
@Table(name = "member_photos", uniqueConstraints = @UniqueConstraint(columnNames = "member_id"))
@Getter @Setter @NoArgsConstructor
public class MemberPhoto {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @Lob
    @Column(nullable = false, length = 5_000_000)
    private byte[] data;

    private String updatedBy;
    private Instant updatedAt = Instant.now();
}
