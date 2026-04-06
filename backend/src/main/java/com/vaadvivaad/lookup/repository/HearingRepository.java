package com.vaadvivaad.lookup.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vaadvivaad.lookup.entity.Hearing;

@Repository
public interface HearingRepository extends JpaRepository<Hearing, UUID> {

    List<Hearing> findByCourtCaseIdOrderByHearingDateDesc(UUID caseId);

    @Query("SELECT h FROM Hearing h JOIN FETCH h.courtCase WHERE h.nextHearingDate  = :date")
    List<Hearing> findByNextHearingDate(@Param("date") LocalDate date);
    
    /*
     * WHY @Modifying + @Query instead of a derived query?
     *
     * Spring Data derived queries for delete return the deleted entities
     * (first loads, then deletes each). For 10 hearings that means 10 SELECTs
     * then 10 DELETEs.
     *
     * @Modifying + @Query generates a single DELETE SQL:
     * DELETE FROM hearings WHERE case_id = ?
     *
     * This is the correct approach for batch deletes.
     * ALWAYS combine @Modifying with @Transactional (the @Transactional on
     * ScraperService.upsertCase covers this — @Modifying methods MUST run
     * within a transaction).
     */
    @Modifying
    @Query("DELETE FROM Hearing h WHERE h.courtCase.id = :caseId")
    void deleteAllByCaseId(@Param("caseId") UUID caseId);
}