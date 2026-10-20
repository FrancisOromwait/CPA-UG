package com.estates.sacco.web;

import com.estates.sacco.domain.SavingsInterestRun;
import com.estates.sacco.repo.SavingsInterestRunRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.SavingsInterestService;
import com.estates.sacco.service.SavingsInterestService.Line;
import com.estates.sacco.service.SavingsInterestService.Owed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Interest on savings. Preview shows what a month's savings earned; posting books it (it also happens by itself after each month
 * ends). The payout moves the interest owed into the members' savings. Branch admins book and undo for their own branch.
 */
@RestController
@RequestMapping("/api/savings-interest")
@RequiredArgsConstructor
public class SavingsInterestController {
    private final SavingsInterestService service;
    private final SavingsInterestRunRepository runs;
    private final BranchScope scope;

    public record Info(boolean enabled, BigDecimal ratePercent, int dayBasis, String firstMonth) {}

    public record RunDto(Long id, String branchCode, String kind, String period, LocalDate periodEnd, BigDecimal totalAmount, int memberCount,
                         BigDecimal ratePercent, String createdBy, Instant createdAt) {
        static RunDto of(SavingsInterestRun r) {
            return new RunDto(r.getId(), r.getBranchCode(), r.getKind(), r.getPeriod(), r.getPeriodEnd(), r.getTotalAmount(), r.getMemberCount(),
                    r.getRatePercent(), r.getCreatedBy(), r.getCreatedAt());
        }
    }

    public record PreviewView(String month, LocalDate monthEnd, boolean ended, boolean booked, List<Line> lines, BigDecimal total) {}

    public record OwedView(LocalDate date, List<Owed> members, BigDecimal total) {}

    public record MonthReq(@NotBlank String month) {}

    public record PayoutReq(@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {}

    private Long branch(Long branchId) {
        Long b = scope.readBranch(branchId);
        if (b == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a branch");
        return b;
    }

    @GetMapping("/info")
    Info info() {
        return new Info(service.enabled(), service.ratePercent(), service.dayBasis(), service.firstMonth());
    }

    @GetMapping("/runs")
    @Transactional(readOnly = true)
    List<RunDto> list(@RequestParam(required = false) Long branchId) {
        return runs.search(scope.readBranch(branchId)).stream().map(RunDto::of).toList();
    }

    @GetMapping("/preview")
    @Transactional(readOnly = true)
    PreviewView preview(@RequestParam String month, @RequestParam(required = false) Long branchId) {
        YearMonth ym = SavingsInterestService.parse(month);
        Long b = branch(branchId);
        List<Line> lines = service.preview(b, ym);
        return new PreviewView(ym.toString(), ym.atEndOfMonth(), !ym.atEndOfMonth().isAfter(LocalDate.now()),
                runs.existsByBranchIdAndKindAndPeriod(b, SavingsInterestRun.ACCRUAL, ym.toString()), lines,
                lines.stream().map(Line::interest).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    RunDto post(@Valid @RequestBody MonthReq req) {
        return RunDto.of(service.post(scope.writeBranch(), SavingsInterestService.parse(req.month()), scope.me().getUsername()));
    }

    @DeleteMapping("/runs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long id) {
        service.delete(id, scope.writeBranch(), scope.me().getUsername());
    }

    @GetMapping("/owed")
    OwedView owed(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestParam(required = false) Long branchId) {
        List<Owed> list = service.owed(branch(branchId), date);
        return new OwedView(date, list, list.stream().map(Owed::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @PostMapping("/payout")
    @ResponseStatus(HttpStatus.CREATED)
    RunDto payout(@Valid @RequestBody PayoutReq req) {
        return RunDto.of(service.payout(scope.writeBranch(), req.date(), scope.me().getUsername()));
    }
}
