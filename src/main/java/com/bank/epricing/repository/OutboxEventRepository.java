package com.bank.epricing.repository;

import com.bank.epricing.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for Transactional Outbox polling, multi-pod lease claiming, and operator DLQ replay.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("""
        SELECT e FROM OutboxEvent e
        WHERE (e.status = 'PENDING' AND e.nextRetryAt <= :now)
           OR (e.status = 'PROCESSING' AND e.lockedUntil < :now)
        ORDER BY e.nextRetryAt ASC, e.id ASC
        """)
    List<OutboxEvent> findDispatchCandidates(@Param("now") Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE OutboxEvent e
        SET e.status = 'PROCESSING',
            e.lockedBy = :workerId,
            e.lockedUntil = :leaseUntil,
            e.version = e.version + 1
        WHERE e.id = :id
          AND ((e.status = 'PENDING' AND e.nextRetryAt <= :now)
            OR (e.status = 'PROCESSING' AND e.lockedUntil < :now))
        """)
    int claimEvent(@Param("id") Long id,
                   @Param("workerId") String workerId,
                   @Param("now") Instant now,
                   @Param("leaseUntil") Instant leaseUntil);

    long countByStatus(OutboxEvent.OutboxStatus status);

    List<OutboxEvent> findByStatusOrderByCreatedAtDesc(OutboxEvent.OutboxStatus status, Pageable pageable);
}
