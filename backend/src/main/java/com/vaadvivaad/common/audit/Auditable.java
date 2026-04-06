package com.vaadvivaad.common.audit;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Getters and Setters
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}



//@MappedSuperclass     → This class is NOT an entity itself. Its fields are
//inherited by child entities and mapped to THEIR tables.
//No "auditable" table is created.
//
//@EntityListeners      → Registers a listener that intercepts entity lifecycle
//events (persist, update).
//
//AuditingEntityListener → Spring Data's built-in listener that automatically
// sets @CreatedDate and @LastModifiedDate.
//
//@CreatedDate          → Auto-set when entity is first persisted.
//updatable = false means it never changes after creation.
//
//@LastModifiedDate     → Auto-set every time entity is updated.