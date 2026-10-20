package com.estates.sacco.web;

import com.estates.sacco.domain.Member;
import com.estates.sacco.domain.MobileMoneyNumber;
import com.estates.sacco.repo.LedgerRepository;
import com.estates.sacco.repo.MemberRepository;
import com.estates.sacco.repo.MobileMoneyNumberRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.MobileMoneyMessageParser;
import com.estates.sacco.service.MobileMoneyMessageParser.Parsed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads a pasted mobile money message (SMS or WhatsApp) so a deposit does not have to be typed: it finds the amount, the sender,
 * the transaction ID and the member whose phone number matches. Nothing is posted here; the deposit form is filled in and the
 * person recording it checks and saves. A transaction ID that was already recorded is flagged so it is not posted twice.
 */
@RestController
@RequestMapping("/api/mobile-payments")
@RequiredArgsConstructor
public class MobilePaymentController {
    /** Deposits made from a mobile money message carry this reference prefix followed by the transaction ID. */
    public static final String REF_PREFIX = "MoMo ";

    private final MemberRepository members;
    private final MobileMoneyNumberRepository numbers;
    private final LedgerRepository ledger;
    private final BranchScope scope;

    public record ParseReq(@NotBlank String text) {}

    public record ParseResult(BigDecimal amount, String senderPhone, String senderName, String transactionId, String reference,
                              LocalDate date, String network, Long memberId, String memberNo, String memberName,
                              Long receivingAccountId, boolean duplicate) {}

    @PostMapping("/parse")
    @Transactional(readOnly = true)
    ParseResult parse(@Valid @RequestBody ParseReq req) {
        Long branchId = scope.writeBranch();
        Parsed p = MobileMoneyMessageParser.parse(req.text());
        if (p.amount() == null) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not find an amount in that message. Paste the whole \"you have received\" text.");
        }
        Member member = p.senderPhone() == null ? null : members.search(branchId, null).stream()
                .filter(m -> sameNumber(m.getPhone(), p.senderPhone())).findFirst().orElse(null);
        String ref = p.transactionId() == null ? null : REF_PREFIX + p.transactionId();
        boolean duplicate = ref != null && ledger.existsByBranchIdAndReference(branchId, ref);

        // the branch's own number on that network receives the money; otherwise leave the choice to the person
        List<MobileMoneyNumber> mine = numbers.findByBranchIdOrderByIdAsc(branchId).stream().filter(MobileMoneyNumber::isActive).toList();
        MobileMoneyNumber receiving = mine.stream().filter(n -> p.network() != null && n.getNetwork().equalsIgnoreCase(p.network())).findFirst()
                .orElse(mine.size() == 1 ? mine.get(0) : null);
        return new ParseResult(p.amount(), p.senderPhone(), p.senderName(), p.transactionId(), ref, p.date(), p.network(),
                member == null ? null : member.getId(), member == null ? null : member.getMemberNo(), member == null ? null : member.getFullName(),
                receiving == null ? null : receiving.getAccount().getId(), duplicate);
    }

    /** Two phone numbers are the same when their last nine digits match (0772123456 = +256772123456). */
    static boolean sameNumber(String a, String b) {
        String x = digits(a), y = digits(b);
        return x.length() >= 9 && y.length() >= 9 && x.substring(x.length() - 9).equals(y.substring(y.length() - 9));
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }
}
