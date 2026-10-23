package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One message sent to a group of members (the Messages screen). Each member's copy and its result is an {@link SmsRecipient}. */
@Entity
@Table(name = "sms_batches")
@Getter @Setter @NoArgsConstructor
public class SmsBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** Who it went to, in words: "All members", "Members with a loan", "Members of ICPAU", "12 chosen members". */
    @Column(nullable = false, length = 120)
    private String audience;

    /** The message as written, with any {name}-style fields still in it. */
    @Column(nullable = false, length = 1000)
    private String text;

    private int recipients;
    private int sent;
    private int failed;

    /** Test mode: prepared and logged only, nothing was sent. */
    private boolean testMode;

    /** SENDING while the members are being worked through, then DONE. */
    @Column(nullable = false, length = 10)
    private String status = "SENDING";

    private String createdBy;
    private Instant createdAt = Instant.now();
}
