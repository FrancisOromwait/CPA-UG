package com.estates.sacco.web;

import com.estates.sacco.domain.AppUser;
import com.estates.sacco.domain.Member;
import com.estates.sacco.repo.UserRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.FinancialReportService;
import com.estates.sacco.service.PdfReportService;
import com.estates.sacco.service.ReportDoc;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** What a member's own login can do, and nothing else: view and download their own statement, and change their password. */
@RestController
@RequestMapping("/api/my")
@RequiredArgsConstructor
public class MemberPortalController {
    private final UserRepository users;
    private final FinancialReportService reports;
    private final PdfReportService pdf;
    private final PasswordEncoder encoder;
    private final BranchScope scope;
    private final com.estates.sacco.repo.MobileMoneyNumberRepository numbers;

    public record PasswordReq(@NotBlank @Size(min = 8, message = "must be at least 8 characters") String password) {}

    private AppUser user() {
        return users.findById(scope.me().getUserId()).orElseThrow();
    }

    private Member member() {
        Member m = user().getMember();
        if (m == null) throw new ApiException(HttpStatus.FORBIDDEN, "This login is not linked to a member");
        return m;
    }

    @GetMapping("/statement")
    @Transactional(readOnly = true)
    ReportDoc statement(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.memberStatement(member(), from, to == null ? LocalDate.now() : to);
    }

    @GetMapping("/statement/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> statementPdf(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReportDoc doc = statement(from, to);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("member-statement-" + LocalDate.now() + ".pdf").build().toString())
                .body(pdf.render(doc, scope.me().getUsername()));
    }

    public record PayTo(String network, String number, String holderName) {}

    /** The branch's mobile money numbers, so the member knows where to send money. */
    @GetMapping("/pay-to")
    @Transactional(readOnly = true)
    java.util.List<PayTo> payTo() {
        return numbers.findByBranchIdOrderByIdAsc(member().getBranch().getId()).stream().filter(n -> n.isActive())
                .map(n -> new PayTo(n.getNetwork(), n.getNumber(), n.getHolderName())).toList();
    }

    @PutMapping("/password")
    @Transactional
    void changePassword(@Valid @RequestBody PasswordReq req) {
        AppUser u = user();
        u.setPasswordHash(encoder.encode(req.password()));
        users.save(u);
    }
}
