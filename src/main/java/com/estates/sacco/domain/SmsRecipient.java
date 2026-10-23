package com.estates.sacco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One member's copy of a message and what happened to it. */
@Entity
@Table(name = "sms_recipients", indexes = @Index(columnList = "batch_id"))
@Getter @Setter @NoArgsConstructor
public class SmsRecipient {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "batch_id")
    private SmsBatch batch;

    @ManyToOne
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(length = 20)
    private String memberNo;

    private String memberName;

    /** The number the message went to, as +256..., or what was on the member's record when it was not a usable mobile number. */
    @Column(length = 60)
    private String phone;

    /** The message with this member's details filled in. */
    @Column(length = 1000)
    private String message;

    /** PENDING, SENT, FAILED, TEST (test mode: not sent) or NO_NUMBER. */
    @Column(nullable = false, length = 10)
    private String status = "PENDING";

    /** What the SMS provider answered: its message id, the cost, or why it failed. */
    @Column(length = 100)
    private String providerId;
    @Column(length = 30)
    private String cost;
    @Column(length = 255)
    private String error;

    private Instant sentAt;
}
