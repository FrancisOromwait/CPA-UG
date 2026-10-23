package com.estates.sacco.service;

import com.estates.sacco.domain.Branch;
import com.estates.sacco.domain.Member;
import com.estates.sacco.domain.SmsBatch;
import com.estates.sacco.domain.SmsRecipient;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.repo.MemberRepository;
import com.estates.sacco.repo.SmsBatchRepository;
import com.estates.sacco.repo.SmsRecipientRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.web.ApiException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * SMS to members: choose who (all members, those with a loan, one employer's, or chosen ones), write the message, preview, send.
 * A message may carry the member's own details: {name}, {memberNo}, {savings}, {shares}, {loan}. Sending is worked through in the
 * background and every member's result is kept. In test mode nothing is sent (see {@link SmsSettings}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService {
    private final MemberRepository members;
    private final BranchRepository branches;
    private final SmsBatchRepository batches;
    private final SmsRecipientRepository recipients;
    private final FinancialReportService reports;
    private final SmsSettings settings;
    private final AfricasTalkingSms africasTalking;
    private final BranchScope scope;
    private final AuditService audit;
    private final TransactionTemplate tx;

    /** One sending at a time, in the background, so the screen does not wait for hundreds of messages. */
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sms-sender");
        t.setDaemon(true);
        return t;
    });

    /** Who gets the message: kind ALL, LOAN (a loan balance), EMPLOYER (with {@code employer}) or CHOSEN (with {@code memberIds}). */
    public record Audience(String kind, String employer, List<Long> memberIds) {}

    /** A member's copy: phone null when the member has no usable mobile number. */
    public record Planned(Member member, String phone, String text) {}

    public record Sample(String memberNo, String name, String phone, String text) {}
    public record NoNumber(String memberNo, String name, String phoneOnRecord) {}
    public record Preview(String audience, int members, int withNumber, int smsPerMessage, int totalSms, boolean live,
                          List<Sample> samples, List<NoNumber> noNumber) {}

    private static final Pattern FIELD = Pattern.compile("\\{(name|memberNo|savings|shares|loan)}", Pattern.CASE_INSENSITIVE);
    /** The characters an SMS carries in its plain alphabet (GSM 03.38); any other character makes every part shorter. */
    private static final Pattern GSM = Pattern.compile("^[A-Za-z0-9 \\r\\n@£$¥èéùìòÇØøÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ!\"#¤%&'()*+,\\-./:;<=>?¡ÄÖÑÜ§¿äöñüà^{}\\\\\\[~\\]|€]*$");

    @PreDestroy
    void stop() {
        sender.shutdown();
    }

    // ------------------------------------------------------------------ planning
    public Preview preview(Audience audience, String text) {
        Long branchId = scope.writeBranch();
        List<Planned> plan = plan(branchId, audience, requireText(text));
        List<Planned> reachable = plan.stream().filter(p -> p.phone() != null).toList();
        int perMessage = reachable.stream().mapToInt(p -> smsParts(p.text())).max().orElse(smsParts(text));
        int total = reachable.stream().mapToInt(p -> smsParts(p.text())).sum();
        List<Sample> samples = reachable.stream().limit(3).map(p -> new Sample(p.member().getMemberNo(), p.member().getFullName(), p.phone(), p.text())).toList();
        List<NoNumber> none = plan.stream().filter(p -> p.phone() == null)
                .map(p -> new NoNumber(p.member().getMemberNo(), p.member().getFullName(), p.member().getPhone())).toList();
        return new Preview(describe(audience, plan.size()), plan.size(), reachable.size(), perMessage, total, settings.isLive(), samples, none);
    }

    /** The members the audience covers, each with their mobile number (or none) and their copy of the message. */
    List<Planned> plan(Long branchId, Audience audience, String text) {
        String kind = audience == null || audience.kind() == null ? "ALL" : audience.kind().toUpperCase(Locale.ROOT);
        List<Member> chosen = switch (kind) {
            case "ALL" -> members.search(branchId, null).stream().filter(Member::isActive).toList();
            case "EMPLOYER" -> {
                String employer = audience.employer() == null ? "" : audience.employer().trim();
                if (employer.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose the employer");
                yield members.search(branchId, null).stream().filter(Member::isActive)
                        .filter(m -> m.getEmployer() != null && m.getEmployer().trim().equalsIgnoreCase(employer)).toList();
            }
            case "LOAN" -> {
                Map<Long, BigDecimal[]> balances = reports.balancesByMember(branchId, LocalDate.now());
                yield members.search(branchId, null).stream().filter(Member::isActive).filter(m -> {
                    BigDecimal[] b = balances.get(m.getId());
                    return b != null && b[FinancialReportService.LOAN].signum() > 0;
                }).toList();
            }
            case "CHOSEN" -> {
                if (audience.memberIds() == null || audience.memberIds().isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose at least one member");
                yield members.findAllById(audience.memberIds()).stream().filter(m -> m.getBranch().getId().equals(branchId)).toList();
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown group of members: " + kind);
        };
        List<Member> sorted = new ArrayList<>(chosen);
        sorted.sort((a, b) -> FinancialReportService.compareMemberNo(a.getMemberNo(), b.getMemberNo()));

        boolean needsBalances = FIELD.matcher(text).results().map(r -> r.group(1).toLowerCase(Locale.ROOT))
                .anyMatch(f -> f.equals("savings") || f.equals("shares") || f.equals("loan"));
        Map<Long, BigDecimal[]> balances = needsBalances ? reports.balancesByMember(branchId, LocalDate.now()) : Map.of();
        List<Planned> out = new ArrayList<>();
        for (Member m : sorted) {
            BigDecimal[] b = balances.getOrDefault(m.getId(), FinancialReportService.zeros());
            out.add(new Planned(m, ugandanMobile(m.getPhone()), fill(text, m, b)));
        }
        return out;
    }

    // ------------------------------------------------------------------ sending
    @Transactional
    public SmsBatch send(Audience audience, String text) {
        Long branchId = scope.writeBranch();
        List<Planned> plan = plan(branchId, audience, requireText(text));
        if (plan.stream().noneMatch(p -> p.phone() != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "None of these members has a mobile number that can receive an SMS");
        }
        Branch branch = branches.findById(branchId).orElseThrow();
        SmsBatch batch = new SmsBatch();
        batch.setBranch(branch);
        batch.setAudience(describe(audience, plan.size()));
        batch.setText(text.trim());
        batch.setRecipients(plan.size());
        batch.setTestMode(!settings.isLive());
        batch.setCreatedBy(scope.me().getUsername());
        batches.save(batch);
        List<SmsRecipient> rows = new ArrayList<>();
        for (Planned p : plan) {
            SmsRecipient r = new SmsRecipient();
            r.setBatch(batch);
            r.setMember(p.member());
            r.setMemberNo(p.member().getMemberNo());
            r.setMemberName(p.member().getFullName());
            r.setPhone(p.phone() != null ? p.phone() : cut(p.member().getPhone(), 60));
            r.setMessage(p.text());
            r.setStatus(p.phone() != null ? "PENDING" : "NO_NUMBER");
            rows.add(r);
        }
        recipients.saveAll(rows);
        batch.setFailed((int) rows.stream().filter(r -> r.getStatus().equals("NO_NUMBER")).count());
        audit.log(branch, "SEND", "Message", batch.getId(), (batch.isTestMode() ? "Test message (not sent) to " : "SMS to ")
                + batch.getAudience() + ": " + cut(batch.getText(), 150));
        Long id = batch.getId();
        // start sending once this is saved, so the background work sees the rows
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sender.submit(() -> deliver(id));
            }
        });
        return batch;
    }

    /** Works through a batch's waiting members: the same text goes out to up to 100 numbers per request. */
    void deliver(Long batchId) {
        try {
            boolean test = Boolean.TRUE.equals(tx.execute(s -> batches.findById(batchId).orElseThrow().isTestMode()));
            List<SmsRecipient> waiting = tx.execute(s -> recipients.findByBatchIdAndStatusOrderByIdAsc(batchId, "PENDING"));
            Map<String, List<SmsRecipient>> byText = new LinkedHashMap<>();
            for (SmsRecipient r : waiting) byText.computeIfAbsent(r.getMessage(), k -> new ArrayList<>()).add(r);
            for (Map.Entry<String, List<SmsRecipient>> e : byText.entrySet()) {
                List<SmsRecipient> group = e.getValue();
                for (int i = 0; i < group.size(); i += 100) {
                    List<SmsRecipient> part = group.subList(i, Math.min(i + 100, group.size()));
                    Map<String, AfricasTalkingSms.Result> results = test ? Map.of()
                            : africasTalking.send(part.stream().map(SmsRecipient::getPhone).distinct().toList(), e.getKey());
                    Instant now = Instant.now();
                    for (SmsRecipient r : part) {
                        if (test) {
                            r.setStatus("TEST");
                        } else {
                            AfricasTalkingSms.Result res = results.get(r.getPhone());
                            r.setStatus(res != null && res.ok() ? "SENT" : "FAILED");
                            if (res != null) {
                                r.setProviderId(cut(res.providerId(), 100));
                                r.setCost(cut(res.cost(), 30));
                                r.setError(cut(res.error(), 255));
                            } else {
                                r.setError("No answer for this number");
                            }
                        }
                        r.setSentAt(now);
                    }
                    tx.executeWithoutResult(s -> recipients.saveAll(part));
                }
            }
        } catch (Exception e) {
            log.error("Sending messages of batch {} stopped: {}", batchId, e.getMessage(), e);
            tx.executeWithoutResult(s -> recipients.findByBatchIdAndStatusOrderByIdAsc(batchId, "PENDING").forEach(r -> {
                r.setStatus("FAILED");
                r.setError(cut("Sending stopped: " + e.getMessage(), 255));
            }));
        } finally {
            tx.executeWithoutResult(s -> batches.findById(batchId).ifPresent(b -> {
                List<SmsRecipient> all = recipients.findByBatchIdOrderByIdAsc(batchId);
                b.setSent((int) all.stream().filter(r -> r.getStatus().equals("SENT") || r.getStatus().equals("TEST")).count());
                b.setFailed((int) all.stream().filter(r -> r.getStatus().equals("FAILED") || r.getStatus().equals("NO_NUMBER")).count());
                b.setStatus("DONE");
            }));
        }
    }

    // ------------------------------------------------------------------ helpers
    /**
     * The first Ugandan mobile number in what is on the member's record, as +2567XXXXXXXX; null when there is none.
     * Records often hold two numbers ("0772123456/0701123456"): the first usable one is taken.
     */
    public static String ugandanMobile(String raw) {
        if (raw == null) return null;
        for (String part : raw.split("[/,;&]|\\bor\\b")) {
            String d = part.replaceAll("[^0-9]", "");
            String national = d.length() == 10 && d.startsWith("07") ? d.substring(1)
                    : d.length() == 12 && d.startsWith("2567") ? d.substring(3)
                    : d.length() == 9 && d.startsWith("7") ? d : null;
            if (national != null) return "+256" + national;
        }
        return null;
    }

    /** How many SMS one message takes: 160 plain characters in one, 153 a part when longer; 70 and 67 with other characters. */
    public static int smsParts(String text) {
        int n = text.length();
        boolean plain = GSM.matcher(text).matches();
        int single = plain ? 160 : 70, part = plain ? 153 : 67;
        return n <= single ? 1 : (n + part - 1) / part;
    }

    private static String fill(String text, Member m, BigDecimal[] b) {
        return FIELD.matcher(text.trim()).replaceAll(r -> java.util.regex.Matcher.quoteReplacement(switch (r.group(1).toLowerCase(Locale.ROOT)) {
            case "name" -> m.getFullName() == null ? "" : m.getFullName().replace(',', ' ').replaceAll("\\s+", " ").trim();
            case "memberno" -> m.getMemberNo();
            case "savings" -> amount(b[FinancialReportService.SAVINGS]);
            case "shares" -> amount(b[FinancialReportService.SHARES]);
            default -> amount(b[FinancialReportService.LOAN]);
        }));
    }

    private static String amount(BigDecimal v) {
        return String.format(Locale.US, "%,.0f", v);
    }

    private static String requireText(String text) {
        if (text == null || text.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Write the message");
        if (text.trim().length() > 900) throw new ApiException(HttpStatus.BAD_REQUEST, "The message is too long (at most 900 characters)");
        return text.trim();
    }

    private static String describe(Audience a, int count) {
        String kind = a == null || a.kind() == null ? "ALL" : a.kind().toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "LOAN" -> "Members with a loan (" + count + ")";
            case "EMPLOYER" -> cut("Members of " + a.employer().trim(), 100) + " (" + count + ")";
            case "CHOSEN" -> count + (count == 1 ? " chosen member" : " chosen members");
            default -> "All members (" + count + ")";
        };
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
