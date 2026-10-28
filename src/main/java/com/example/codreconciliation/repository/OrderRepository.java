package com.example.codreconciliation.repository;

import java.util.List;
import java.util.Optional;

import com.example.codreconciliation.entity.Order;
import com.example.codreconciliation.enums.OrderStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByTrackingNumber(String trackingNumber);
    Optional<Order> findByTrackingNumberIgnoreCase(String trackingNumber);
    boolean existsByTrackingNumberIgnoreCase(String trackingNumber);
    List<Order> findByTrackingNumberContainingIgnoreCaseOrCustomerNameContainingIgnoreCase(
            String trackingNumber, String customerName);
    List<Order> findByStatus(OrderStatus status);
    @Query("""
            select o from Order o
            where (:search is null or lower(o.trackingNumber) like lower(concat('%', :search, '%'))
                or lower(o.customerName) like lower(concat('%', :search, '%')))
              and (:status is null or o.status = :status)
            order by o.createdAt desc
            """)
    List<Order> search(@Param("search") String search, @Param("status") OrderStatus status);
    long countByStatus(OrderStatus status);

    @Query("select coalesce(sum(o.expectedAmount), 0) from Order o where o.status = :status")
    java.math.BigDecimal sumExpectedAmountByStatus(@Param("status") OrderStatus status);
}
