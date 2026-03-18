package org.codeNbug.mainserver.domain.purchase.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 사전 등록 시 선택된 좌석 정보를 영속화하는 엔티티
 *
 * 목적: Webhook 장애 복구 시 Redis 락이 소실된 경우에도 DB에서 좌석 정보를 조회할 수 있도록 한다.
 * 생성: /payments/init 시점
 * 소멸: 결제 완료(DONE) 또는 취소(CANCELED) 후에도 감사 목적으로 보존
 */
@Entity
@Table(name = "purchase_seat")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Builder
public class PurchaseSeat {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "purchase_id", nullable = false)
	private Purchase purchase;

	@Column(nullable = false)
	private Long seatId;

	@Column(nullable = false)
	private Long eventId;
}
