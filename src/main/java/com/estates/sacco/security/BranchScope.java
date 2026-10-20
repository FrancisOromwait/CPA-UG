package com.estates.sacco.security;

import com.estates.sacco.domain.Role;
import com.estates.sacco.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single place that decides which branch's data the caller may touch.
 * A branch admin is always pinned to their own branch; the manager may see any or all.
 */
@Component
public class BranchScope {

    public SaccoPrincipal me() {
        return (SaccoPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    public boolean isManager() {
        return me().getRole() == Role.MANAGER;
    }

    /** Branch filter for reads: null means all branches, and is only possible for the manager. */
    public Long readBranch(Long requested) {
        if (isManager()) return requested;
        if (requested != null && !requested.equals(me().getBranchId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only access your own branch");
        }
        return me().getBranchId();
    }

    /** Branch for writes (admins only, enforced in SecurityConfig). */
    public Long writeBranch() {
        Long id = me().getBranchId();
        if (id == null) throw new ApiException(HttpStatus.FORBIDDEN, "Only a branch admin can record transactions");
        return id;
    }

    public void assertAccess(Long branchId) {
        if (isManager()) return;
        if (!me().getBranchId().equals(branchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only access your own branch");
        }
    }
}
