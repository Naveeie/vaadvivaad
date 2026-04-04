package com.vaadvivaad.lookup.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.vaadvivaad.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "subscriptions", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "case_id"})
})
@EntityListeners(AuditingEntityListener.class)
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_id", nullable = false)
    private CourtCase courtCase;

    @Column(name = "notify_whatsapp", nullable = false)
    private boolean notifyWhatsapp = true;

    @Column(name = "notify_sms", nullable = false)
    private boolean notifySms = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Default constructor required by JPA
    public Subscription() {
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public CourtCase getCourtCase() {
        return courtCase;
    }

    public void setCourtCase(CourtCase courtCase) {
        this.courtCase = courtCase;
    }

    public boolean isNotifyWhatsapp() {
        return notifyWhatsapp;
    }

    public void setNotifyWhatsapp(boolean notifyWhatsapp) {
        this.notifyWhatsapp = notifyWhatsapp;
    }

    public boolean isNotifySms() {
        return notifySms;
    }

    public void setNotifySms(boolean notifySms) {
        this.notifySms = notifySms;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
