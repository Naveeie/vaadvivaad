package com.vaadvivaad.lookup.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.vaadvivaad.lookup.entity.CourtCase;

@Repository
public interface CourtCaseRepository extends JpaRepository<CourtCase, UUID> {

    Optional<CourtCase> findByCnrNumber(String cnrNumber);

    boolean existsByCnrNumber(String cnrNumber);
}