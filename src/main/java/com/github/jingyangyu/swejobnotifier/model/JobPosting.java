package com.github.jingyangyu.swejobnotifier.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** JPA entity representing a job posting scraped from a company career site. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"company", "externalId"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String company;

    @Column(nullable = false)
    private String externalId;

    @Column(nullable = false)
    private String title;

    @Column(length = 2048)
    private String url;

    private String location;

    @Column(columnDefinition = "CLOB")
    private String description;

    private Instant postedDate;

    @Column(nullable = false)
    private Instant detectedAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean notified = false;

    /** Whether this job was classified as mid-level SWE by Gemini (Y). */
    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    @Builder.Default
    private boolean midLevel = false;
}
