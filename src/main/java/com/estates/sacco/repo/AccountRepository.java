package com.estates.sacco.repo;

import com.estates.sacco.domain.Account;
import com.estates.sacco.domain.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    boolean existsByCode(String code);
    boolean existsByParentId(Long parentId);
    Optional<Account> findByCode(String code);

    @Query("select a from Account a where (:type is null or a.type = :type) order by a.code")
    List<Account> search(AccountType type);
}
