package com.estates.sacco.web;


import com.estates.sacco.domain.Employer;
import com.estates.sacco.repo.EmployerRepository;
import com.estates.sacco.security.BranchScope;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/employers")
@RequiredArgsConstructor

public class EmployerController {
    private final EmployerRepository employers;
    private final BranchScope scope;

    @GetMapping
    @Transactional(readOnly = true)
    List<Employer> list() {
        return employers.findByBranchIdOrderByNameAsc(scope.readBranch(null));
    }
    
}


