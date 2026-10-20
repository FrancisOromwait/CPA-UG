package com.estates.sacco.web;

import com.estates.sacco.security.BranchScope;
import com.estates.sacco.service.ReportService;
import com.estates.sacco.web.Dtos.ConsolidatedReport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {
    private final ReportService reports;
    private final BranchScope scope;

    /**
     * Manager: every branch plus group totals (or one branch via branchId).
     * Branch admin: always only their own branch.
     */
    @GetMapping("/summary")
    ConsolidatedReport summary(@RequestParam(required = false) Long branchId) {
        return reports.summary(scope.readBranch(branchId));
    }
}
