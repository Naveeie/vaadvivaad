package com.vaadvivaad.lookup.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vaadvivaad.lookup.entity.Hearing;

@Repository
public interface HearingRepository extends JpaRepository<Hearing, UUID> {

    List<Hearing> findByCourtCaseIdOrderByHearingDateDesc(UUID caseId);

    @Query("SELECT h FROM Hearing h JOIN FETCH h.courtCase WHERE h.hearingDate = :date")
    List<Hearing> findByHearingDate(@Param("date") LocalDate date);
}