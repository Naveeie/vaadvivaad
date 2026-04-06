// lookup/entity/Hearing.java

package com.vaadvivaad.lookup.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "hearings")
public class Hearing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private CourtCase courtCase;

    @Column(name = "hearing_date", nullable = false)
    private LocalDate hearingDate;

    @Column(name = "purpose", length = 500)
    private String purpose;

    /*
     * Renamed from "detail" to "notes" — matches V7 migration.
     * Stores the court clerk's notes from that hearing day.
     * Also used by Claude in Session 8 as input for Hindi summary.
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /*
     * The date the judge set for the next appearance.
     * This is what the scheduler should use to send reminders —
     * not hearing_date (which is when the past hearing occurred).
     *
     * Example:
     *   hearing_date = 2024-05-10 (hearing happened)
     *   next_hearing_date = 2024-06-15 (judge said "come back June 15")
     *   Reminder fires on 2024-06-14
     */
    @Column(name = "next_hearing_date")
    private LocalDate nextHearingDate;

    /*
     * Populated in Session 8 after Claude processes this hearing.
     * Null until AI summary is generated.
     */
    @Column(name = "ai_summary_hindi", columnDefinition = "TEXT")
    private String aiSummaryHindi;

    @Column(name = "created_at", updatable = false)
    private java.time.LocalDateTime createdAt;

    // Getters and setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public CourtCase getCourtCase() { return courtCase; }
    public void setCourtCase(CourtCase courtCase) { this.courtCase = courtCase; }

    public LocalDate getHearingDate() { return hearingDate; }
    public void setHearingDate(LocalDate hearingDate) { this.hearingDate = hearingDate; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDate getNextHearingDate() { return nextHearingDate; }
    public void setNextHearingDate(LocalDate nextHearingDate) {
        this.nextHearingDate = nextHearingDate;
    }

    public String getAiSummaryHindi() { return aiSummaryHindi; }
    public void setAiSummaryHindi(String aiSummaryHindi) {
        this.aiSummaryHindi = aiSummaryHindi;
    }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}