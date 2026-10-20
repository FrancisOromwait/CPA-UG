package com.estates.sacco.web;

import com.estates.sacco.service.YearCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Generate New Year Codes. Group-wide setup, so manager only (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/year-codes")
@RequiredArgsConstructor
public class YearCodeController {
    private final YearCodeService service;

    /** The year generated so far, the year that would be generated, and what stops it (if anything). */
    @GetMapping
    YearCodeService.Status status() {
        return service.status();
    }

    /** Run New Year. */
    @PostMapping
    YearCodeService.Result run() {
        return service.generate();
    }
}
