package com.estates.sacco.repo;

import com.estates.sacco.domain.SmsRecipient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SmsRecipientRepository extends JpaRepository<SmsRecipient, Long> {
    List<SmsRecipient> findByBatchIdOrderByIdAsc(Long batchId);
    List<SmsRecipient> findByBatchIdAndStatusOrderByIdAsc(Long batchId, String status);
}
