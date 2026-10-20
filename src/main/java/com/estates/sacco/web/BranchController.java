package com.estates.sacco.web;

import com.estates.sacco.domain.Branch;
import com.estates.sacco.repo.BranchRepository;
import com.estates.sacco.security.BranchScope;
import com.estates.sacco.web.Dtos.BranchDto;
import com.estates.sacco.web.Dtos.BranchReq;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/branches")
@RequiredArgsConstructor
public class BranchController {
    private final BranchRepository branches;
    private final BranchScope scope;
    private final com.estates.sacco.service.ProductService productService;

    /** Manager sees every branch; a branch admin sees only their own. */
    @GetMapping
    List<BranchDto> list() {
        if (scope.isManager()) return branches.findAll().stream().map(BranchDto::of).toList();
        return branches.findById(scope.me().getBranchId()).stream().map(BranchDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BranchDto create(@Valid @RequestBody BranchReq req) {
        String code = req.code().trim().toUpperCase();
        if (branches.existsByCodeIgnoreCase(code)) {
            throw new ApiException(HttpStatus.CONFLICT, "Sacco ID " + code + " already exists");
        }
        Branch b = new Branch();
        b.setCode(code);
        b.setName(req.name().trim());
        b.setLocation(req.location());
        Branch saved = branches.save(b);
        productService.seedDefaults(saved);
        return BranchDto.of(saved);
    }

    /** Activate or deactivate a branch (deactivated branches cannot log in). */
    @PutMapping("/{id}/active/{value}")
    BranchDto setActive(@PathVariable Long id, @PathVariable boolean value) {
        Branch b = branches.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Branch not found"));
        b.setActive(value);
        return BranchDto.of(branches.save(b));
    }
}
