package com.estates.sacco.web;

import com.estates.sacco.domain.SmsBatch;
import com.estates.sacco.domain.SmsRecipient;
import com.estates.sacco.repo.SmsBatchRepository;
import com.estates.sacco.repo.SmsRecipientRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.MessageService;
import com.estates.sacco.service.SmsSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** SMS to members (Administration > Messages). The branch administrator sends; the support login can look at what was sent. */
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {
    private final MessageService service;
    private final SmsBatchRepository batches;
    private final SmsRecipientRepository recipients;
    private final SmsSettings settings;
    private final BranchScope scope;

    public record SendReq(String audience, String employer, List<Long> memberIds, String text) {
        MessageService.Audience toAudience() { return new MessageService.Audience(audience, employer, memberIds); }
    }

    public record Settings(boolean live, String provider, String senderId, boolean sandbox) {}

    public record BatchView(Long id, Instant createdAt, String createdBy, String audience, String text, int recipients, int sent,
                            int failed, boolean testMode, String status) {
        static BatchView of(SmsBatch b) {
            return new BatchView(b.getId(), b.getCreatedAt(), b.getCreatedBy(), b.getAudience(), b.getText(), b.getRecipients(),
                    b.getSent(), b.getFailed(), b.isTestMode(), b.getStatus());
        }
    }

    public record RecipientView(String memberNo, String memberName, String phone, String message, String status, String cost,
                                String error, Instant sentAt) {
        static RecipientView of(SmsRecipient r) {
            return new RecipientView(r.getMemberNo(), r.getMemberName(), r.getPhone(), r.getMessage(), r.getStatus(), r.getCost(),
                    r.getError(), r.getSentAt());
        }
    }

    public record BatchDetail(BatchView batch, List<RecipientView> recipients) {}

    /** Whether messages really go out (live) or are only prepared (test mode). The API key itself is never shown. */
    @GetMapping("/settings")
    Settings settings() {
        return new Settings(settings.isLive(), settings.isLive() ? settings.getProvider() : "test", settings.getSenderId(), settings.isSandbox());
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<BatchView> list(@RequestParam(required = false) Long branchId) {
        Long branch = scope.readBranch(branchId);
        return (branch == null ? batches.findTop100ByOrderByIdDesc() : batches.findTop100ByBranchIdOrderByIdDesc(branch))
                .stream().map(BatchView::of).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    BatchDetail view(@PathVariable Long id) {
        SmsBatch b = batches.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Message not found"));
        scope.assertAccess(b.getBranch().getId());
        return new BatchDetail(BatchView.of(b), recipients.findByBatchIdOrderByIdAsc(id).stream().map(RecipientView::of).toList());
    }

    @PostMapping("/preview")
    @Transactional(readOnly = true)
    MessageService.Preview preview(@RequestBody SendReq req) {
        return service.preview(req.toAudience(), req.text());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BatchView send(@RequestBody SendReq req) {
        return BatchView.of(service.send(req.toAudience(), req.text()));
    }
}
