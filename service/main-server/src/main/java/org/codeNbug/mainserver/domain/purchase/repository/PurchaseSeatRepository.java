package org.codeNbug.mainserver.domain.purchase.repository;

import java.util.List;

import org.codeNbug.mainserver.domain.purchase.entity.Purchase;
import org.codeNbug.mainserver.domain.purchase.entity.PurchaseSeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseSeatRepository extends JpaRepository<PurchaseSeat, Long> {
	List<PurchaseSeat> findByPurchase(Purchase purchase);
}
