package org.codeNbug.mainserver.external.toss.webhook.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.codeNbug.mainserver.domain.event.entity.Event;
import org.codeNbug.mainserver.domain.manager.repository.EventRepository;
import org.codeNbug.mainserver.domain.notification.entity.NotificationEnum;
import org.codeNbug.mainserver.domain.notification.service.NotificationService;
import org.codeNbug.mainserver.domain.purchase.entity.PaymentStatusEnum;
import org.codeNbug.mainserver.domain.purchase.entity.Purchase;
import org.codeNbug.mainserver.domain.purchase.entity.PurchaseSeat;
import org.codeNbug.mainserver.domain.purchase.repository.PurchaseRepository;
import org.codeNbug.mainserver.domain.purchase.repository.PurchaseSeatRepository;
import org.codeNbug.mainserver.domain.seat.entity.Seat;
import org.codeNbug.mainserver.domain.seat.repository.SeatRepository;
import org.codeNbug.mainserver.domain.ticket.entity.Ticket;
import org.codeNbug.mainserver.domain.ticket.repository.TicketRepository;
import org.codeNbug.mainserver.external.toss.service.TossPaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 상태 변화 감지 웹훅 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

	private final ObjectMapper objectMapper;
	private final PurchaseRepository purchaseRepository;
	private final PurchaseSeatRepository purchaseSeatRepository;
	private final TicketRepository ticketRepository;
	private final SeatRepository seatRepository;
	private final EventRepository eventRepository;
	private final TossPaymentService tossPaymentService;
	private final NotificationService notificationService;

	@Value("${payment.toss.secret-key}")
	private String tossSecretKey;

	@Transactional
	public void handleWebhook(String payload, String signature) {
		try {
			if (!isValidSignature(payload, signature)) {
				throw new SecurityException("Toss 시그니처 검증 실패");
			}

			JsonNode root = objectMapper.readTree(payload);
			String status = root.path("data").path("status").asText();

			switch (status) {
				case "DONE":
					handleApproved(root);
					break;
				case "CANCELED":
					handleCanceled(root);
					break;
				case "EXPIRED":
					handleExpired(root);
					break;
				default:
					log.warn("처리되지 않은 결제 상태: {}", status);
			}
		} catch (Exception e) {
			log.error("웹훅 처리 실패: ", e);
			throw new RuntimeException("웹훅 처리 실패: " + e.getMessage(), e);
		}
	}

	private boolean isValidSignature(String payload, String receivedSignature) {
		if (receivedSignature == null) {
			log.warn("테스트 환경으로 간주하고 시그니처 검증을 건너뜁니다.");
			return true;
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(tossSecretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
			String expectedSignature = bytesToHex(hash);
			return expectedSignature.equals(receivedSignature);
		} catch (Exception e) {
			log.error("시그니처 검증 오류", e);
			return false;
		}
	}

	private String bytesToHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder();
		for (byte b : bytes) {
			String hex = Integer.toHexString(0xff & b);
			if (hex.length() == 1)
				builder.append("0");
			builder.append(hex);
		}
		return builder.toString();
	}

	public void handleApproved(JsonNode root) {
		JsonNode data = root.path("data");
		String paymentKey = data.path("paymentKey").asText();

		// EC1: 비관적 락으로 중복 Webhook 동시 처리 방지
		Purchase purchase = purchaseRepository.findByPaymentUuidWithLock(paymentKey)
			.orElseThrow(() -> new IllegalArgumentException("[DONE] paymentKey 로 구매 정보를 찾을 수 없습니다."));

		// 멱등성 체크: 이미 DONE이면 /payments/confirm에서 정상 처리된 것 — 중복 처리 방지
		if (PaymentStatusEnum.DONE.equals(purchase.getPaymentStatus())) {
			log.info("[Webhook] 이미 처리된 결제 승인 수신 (정상): paymentKey = {}", paymentKey);
			return;
		}

		// IN_PROGRESS 상태 = /payments/confirm 처리 도중 장애 발생 → 보조 복구 경로
		List<Ticket> tickets = ticketRepository.findAllByPurchaseId(purchase.getId());

		if (!tickets.isEmpty()) {
			// EC2: 티켓은 생성됐지만 paymentStatus 업데이트 전에 장애 발생 → 상태만 DONE으로 복구
			purchase.setPaymentStatus(PaymentStatusEnum.DONE);
			purchaseRepository.save(purchase);
			log.info("[Webhook] 결제 상태 복구 완료 (티켓 존재): paymentKey = {}, ticketCount = {}",
				paymentKey, tickets.size());
			notifyRecovery(purchase, "결제가 정상적으로 처리되었습니다.");
		} else {
			// EC3/EC4: 티켓 미생성 → PurchaseSeat로 좌석 재생성 시도
			List<PurchaseSeat> purchaseSeats = purchaseSeatRepository.findByPurchase(purchase);
			if (purchaseSeats.isEmpty()) {
				// PurchaseSeat도 없음 → 수동 처리 필요
				log.error("[Webhook] 결제 승인됐으나 티켓·좌석 정보 없음 — 수동 처리 필요: paymentKey = {}, purchaseId = {}",
					paymentKey, purchase.getId());
				autoRefund(paymentKey, purchase, "결제는 완료됐으나 좌석 정보가 없어 자동 취소되었습니다.");
			} else {
				recoverTickets(purchase, purchaseSeats);
			}
		}
	}

	/**
	 * PurchaseSeat 기반으로 티켓 및 좌석 예약 상태를 복구한다.
	 * EC3: 좌석이 이미 다른 구매에 할당된 경우 자동 환불
	 * EC4: 좌석을 찾을 수 없는 경우 자동 환불
	 */
	private void recoverTickets(Purchase purchase, List<PurchaseSeat> purchaseSeats) {
		Long eventId = purchaseSeats.get(0).getEventId();
		Event event = eventRepository.findById(eventId).orElse(null);
		if (event == null) {
			log.error("[Webhook] 이벤트 정보를 찾을 수 없어 자동 환불 처리: purchaseId = {}", purchase.getId());
			autoRefund(purchase.getPaymentUuid(), purchase, "이벤트 정보를 찾을 수 없어 자동 취소되었습니다.");
			return;
		}

		List<Long> seatIds = purchaseSeats.stream().map(PurchaseSeat::getSeatId).toList();
		List<Seat> seats = seatRepository.findAllById(seatIds);

		if (seats.size() != seatIds.size()) {
			log.error("[Webhook] 일부 좌석 정보 없음 — 자동 환불 처리: purchaseId = {}", purchase.getId());
			autoRefund(purchase.getPaymentUuid(), purchase, "좌석 정보가 없어 자동 취소되었습니다.");
			return;
		}

		// EC3: 좌석이 이미 다른 사용자에게 예약됐는지 확인
		List<Seat> alreadyTaken = seats.stream()
			.filter(s -> !s.isAvailable() && s.getTicket() != null)
			.toList();

		if (!alreadyTaken.isEmpty()) {
			log.error("[Webhook] 좌석이 이미 점유됨 — 자동 환불 처리: purchaseId = {}, takenSeatIds = {}",
				purchase.getId(), alreadyTaken.stream().map(Seat::getId).toList());
			autoRefund(purchase.getPaymentUuid(), purchase, "선택하신 좌석이 이미 예약되어 자동 취소되었습니다.");
			return;
		}

		// 좌석과 티켓 생성
		List<Ticket> newTickets = seats.stream()
			.map(seat -> {
				seat.reserve();
				Ticket ticket = new Ticket(null, seat.getLocation(), LocalDateTime.now(), event, purchase);
				seat.setTicket(ticket);
				return ticket;
			})
			.toList();

		ticketRepository.saveAll(newTickets);
		seatRepository.saveAll(seats);

		purchase.setPaymentStatus(PaymentStatusEnum.DONE);
		purchaseRepository.save(purchase);

		log.info("[Webhook] 티켓 재생성 및 결제 상태 복구 완료: purchaseId = {}, ticketCount = {}",
			purchase.getId(), newTickets.size());
		notifyRecovery(purchase, "결제가 정상적으로 처리되었습니다.");
	}

	/**
	 * Toss에 자동 취소 요청 후 결제 상태를 CANCELED로 변경하고 사용자에게 알림을 보낸다.
	 */
	private void autoRefund(String paymentKey, Purchase purchase, String reason) {
		try {
			tossPaymentService.cancelPayment(paymentKey, reason);
			purchase.setPaymentStatus(PaymentStatusEnum.CANCELED);
			purchaseRepository.save(purchase);
			log.info("[Webhook] 자동 환불 처리 완료: paymentKey = {}", paymentKey);
			notifyRecovery(purchase, reason);
		} catch (Exception e) {
			log.error("[Webhook] 자동 환불 실패 — 수동 처리 필요: paymentKey = {}, 오류 = {}", paymentKey, e.getMessage());
		}
	}

	private void notifyRecovery(Purchase purchase, String message) {
		try {
			notificationService.createNotification(
				purchase.getUser().getUserId(),
				NotificationEnum.PAYMENT,
				"[결제 처리 완료]",
				message,
				"/my"
			);
		} catch (Exception e) {
			log.warn("[Webhook] 알림 전송 실패 (무시): purchaseId = {}", purchase.getId());
		}
	}

	private void handleCanceled(JsonNode root) {
		JsonNode data = root.path("data");
		String paymentKey = data.path("paymentKey").asText();

		Purchase purchase = purchaseRepository.findByPaymentUuid(paymentKey)
			.orElseThrow(() -> new IllegalArgumentException("[CANCELED] paymentKey 로 구매 정보를 찾을 수 없습니다."));

		purchase.setPaymentStatus(PaymentStatusEnum.CANCELED);
		purchaseRepository.save(purchase);

		log.info("결제 취소 처리 완료: paymentKey = {}", paymentKey);
	}

	private void handleExpired(JsonNode root) {
		JsonNode data = root.path("data");
		String paymentKey = data.path("paymentKey").asText();

		Purchase purchase = purchaseRepository.findByPaymentUuid(paymentKey)
			.orElseThrow(() -> new IllegalArgumentException("[EXPIRED] paymentKey 로 구매 정보를 찾을 수 없습니다."));

		purchase.setPaymentStatus(PaymentStatusEnum.EXPIRED);
		purchaseRepository.save(purchase);
		log.info("결제 만료 처리 완료: paymentKey = {}", paymentKey);
	}
}
