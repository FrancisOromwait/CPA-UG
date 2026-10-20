package com.estates.sacco.web;

import com.estates.sacco.repo.PayrollBatchRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.PayrollService;
import com.estates.sacco.web.Dtos.PayrollDto;
import com.estates.sacco.web.Dtos.PayrollReq;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll")
@RequiredArgsConstructor
public class PayrollController {
    private final PayrollService payroll;
    private final PayrollBatchRepository batches;
    private final BranchScope scope;
    private final com.estates.sacco.service.EntryAdminService admin;
    private final com.estates.sacco.repo.LedgerRepository ledger;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PayrollDto post(@Valid @RequestBody PayrollReq req) {
        return PayrollDto.of(payroll.post(req));
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<PayrollDto> list(@RequestParam(required = false) Long branchId) {
        return batches.search(scope.readBranch(branchId)).stream().map(PayrollDto::of).toList();
    }

    public record PayrollEdit(@jakarta.validation.constraints.NotBlank String employer,
                              @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be yyyy-MM") String period,
                              @jakarta.validation.constraints.NotNull Long receivingAccountId, java.time.LocalDate date) {}

    public record PayrollView(PayrollDto batch, java.util.List<DepositController.DepositDto> entries) {}

    private com.estates.sacco.domain.PayrollBatch load(Long id) {
        com.estates.sacco.domain.PayrollBatch b = batches.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payroll batch not found"));
        scope.assertAccess(b.getBranch().getId());
        return b;
    }

    private PayrollView toView(com.estates.sacco.domain.PayrollBatch b) {
        return new PayrollView(PayrollDto.of(b), ledger.findByPayrollBatchId(b.getId()).stream().map(DepositController.DepositDto::of).toList());
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    PayrollView view(@PathVariable Long id) {
        return toView(load(id));
    }

    @PutMapping("/{id}")
    @Transactional
    PayrollView edit(@PathVariable Long id, @Valid @RequestBody PayrollEdit req) {
        return toView(admin.editBatch(load(id), req.employer(), req.period(), req.receivingAccountId(), req.date()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        admin.deleteBatch(load(id));
    }
}
