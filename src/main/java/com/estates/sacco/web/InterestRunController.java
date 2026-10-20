package com.estates.sacco.web;

import com.estates.sacco.domain.InterestRun;
import com.estates.sacco.repo.InterestRunRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.InterestRunService;
import com.estates.sacco.service.InterestRunService.Line;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Monthly interest computation. Preview shows what a month would charge; posting books it.
 * Branch admins post and delete runs for their own branch; the manager can look at every branch.
 */
@RestController
@RequestMapping("/api/interest-runs")
@RequiredArgsConstructor
public class InterestRunController {
    private final InterestRunService service;
    private final InterestRunRepository runs;
    private final BranchScope scope;

    public record PostReq(@NotBlank String month) {}

    public record RunDto(Long id, Long branchId, String branchCode, String month, LocalDate monthEnd, BigDecimal totalInterest,
                         int loanCount, String createdBy, Instant createdAt) {
        static RunDto of(InterestRun r) {
            return new RunDto(r.getId(), r.getBranch().getId(), r.getBranchCode(), r.getMonth(), r.getMonthEnd(), r.getTotalInterest(),
                    r.getLoanCount(), r.getCreatedBy(), r.getCreatedAt());
        }
    }

    public record PreviewView(String month, LocalDate monthEnd, boolean ended, List<Line> lines, BigDecimal total, List<RunDto> runsThisMonth) {}

    public record RunView(RunDto run, List<Line> lines) {}

    @GetMapping
    @Transactional(readOnly = true)
    List<RunDto> list(@RequestParam(required = false) Long branchId) {
        return runs.search(scope.readBranch(branchId)).stream().map(RunDto::of).toList();
    }

    @GetMapping("/preview")
    @Transactional(readOnly = true)
    PreviewView preview(@RequestParam String month, @RequestParam(required = false) Long branchId) {
        YearMonth ym = InterestRunService.parse(month);
        Long scoped = scope.readBranch(branchId);
        List<Line> lines = service.preview(scoped, ym);
        List<RunDto> existing = scoped == null ? List.of()
                : runs.findByBranchIdAndMonthOrderByIdAsc(scoped, ym.toString()).stream().map(RunDto::of).toList();
        return new PreviewView(ym.toString(), ym.atEndOfMonth(), !ym.atEndOfMonth().isAfter(LocalDate.now()), lines,
                lines.stream().map(Line::interest).reduce(BigDecimal.ZERO, BigDecimal::add), existing);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    RunView post(@Valid @RequestBody PostReq req) {
        return view(service.post(InterestRunService.parse(req.month())));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    RunView get(@PathVariable Long id) {
        return view(service.load(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        service.delete(id);
    }

    private RunView view(InterestRun run) {
        return new RunView(RunDto.of(run), service.lines(run));
    }

}
