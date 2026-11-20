package com.bbcinema.entity;

import com.bbcinema.entity.enums.StockRequestStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A goods-received note for one cinema's snack bar, entered by the counter manager. Stock only goes up once the
 * general manager approves it.
 */
@Entity
@Table(name = "stock_requests")
@Getter
@Setter
@NoArgsConstructor
public class StockRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cinema_id")
    private Cinema cinema;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StockRequestStatus status = StockRequestStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id")
    private User requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(length = 255)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 255)
    private String reviewNote;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<StockRequestLine> lines = new ArrayList<>();

    public boolean isPending() {
        return status == StockRequestStatus.PENDING;
    }
}
