package com.vaadvivaad.lookup.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.vaadvivaad.lookup.entity.Subscription;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    List<Subscription> findByUserId(UUID userId);

    List<Subscription> findByCourtCaseId(UUID caseId);

    Optional<Subscription> findByUserIdAndCourtCaseId(UUID userId, UUID caseId);

    boolean existsByUserIdAndCourtCaseId(UUID userId, UUID caseId);
    
    @Query("SELECT DISTINCT c.cnrNumber FROM Subscription s JOIN s.courtCase c")
    List<String> findAllDistinctCnrNumbers();
}