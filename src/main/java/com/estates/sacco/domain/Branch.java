package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A Sacco branch. The code is the Sacco ID the manager uses to identify it. */
@Entity
@Table(name = "branches")
@Getter @Setter @NoArgsConstructor
public class Branch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false)
    private String name;

    private String location;

    private boolean active = true;
}
