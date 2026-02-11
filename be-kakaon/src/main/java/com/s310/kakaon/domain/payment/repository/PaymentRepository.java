package com.s310.kakaon.domain.payment.repository;

import com.s310.kakaon.domain.payment.dto.PaymentMethod;
import com.s310.kakaon.domain.payment.entity.Payment;
import com.s310.kakaon.domain.store.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long>, PaymentRepositoryCustom {
    Boolean existsByAuthorizationNo(String authorizationNo);
    List<Payment> findByStore(Store store);
    Optional<Payment> findByOrder_OrderId(Long orderId);
    List<Payment> findByOrder_OrderIdIn(Collection<Long> orderIds);
    Optional<Payment> findByAuthorizationNo(String authorizationNo);

    @Query("""
        SELECT p FROM Payment p
        WHERE p.store.id = :storeId
        AND p.paymentMethod = :paymentMethod
        AND p.amount = :amount
        AND p.paymentUuid = :paymentUuid
        AND p.approvedAt >= :windowStart
        AND p.approvedAt <= :windowEnd
        AND p.status = 'APPROVED'
        ORDER BY p.approvedAt ASC
        """)
    List<Payment> findPaymentsUsingWhere(
            @Param("storeId") Long storeId,
            @Param("paymentMethod") PaymentMethod paymentMethod,
            @Param("amount") Integer amount,
            @Param("paymentUuid") String paymentUuid,
            @Param("windowStart") LocalDateTime windowStart,
            @Param("windowEnd") LocalDateTime windowEnd
    );
}
