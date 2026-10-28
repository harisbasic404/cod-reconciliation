package com.example.codreconciliation.service;

import java.util.List;

import com.example.codreconciliation.dto.OrderDetailResponse;
import com.example.codreconciliation.dto.OrderRequest;
import com.example.codreconciliation.dto.OrderResponse;
import com.example.codreconciliation.dto.ReconciliationItemResponse;
import com.example.codreconciliation.entity.Order;
import com.example.codreconciliation.enums.OrderStatus;
import com.example.codreconciliation.exception.DuplicateTrackingNumberException;
import com.example.codreconciliation.exception.ResourceNotFoundException;
import com.example.codreconciliation.repository.OrderRepository;
import com.example.codreconciliation.repository.ReconciliationItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ReconciliationItemRepository itemRepository;

    public OrderService(OrderRepository orderRepository, ReconciliationItemRepository itemRepository) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> search(String search, OrderStatus status) {
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();
        return orderRepository.search(normalizedSearch, status).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse get(Long id) {
        Order order = find(id);
        List<ReconciliationItemResponse> history = itemRepository
                .findByTrackingNumberOrderByBatchUploadedAtDesc(order.getTrackingNumber())
                .stream()
                .map(item -> new ReconciliationItemResponse(item.getId(), item.getRowNum(),
                        item.getTrackingNumber(), item.getExpectedAmount(), item.getCollectedAmount(),
                        item.getDeliveryDate(), item.getOutcome(), item.getMessage()))
                .toList();
        return new OrderDetailResponse(toResponse(order), history);
    }

    @Transactional
    public OrderResponse create(OrderRequest request) {
        String trackingNumber = normalizeRequired(request.trackingNumber());
        if (orderRepository.existsByTrackingNumberIgnoreCase(trackingNumber)) {
            throw new DuplicateTrackingNumberException(trackingNumber);
        }
        Order order = new Order(trackingNumber, normalizeRequired(request.customerName()),
                request.expectedAmount(), OrderStatus.PENDING);
        return toResponse(orderRepository.save(order));
    }

    @Transactional
    public OrderResponse update(Long id, OrderRequest request) {
        Order order = find(id);
        String trackingNumber = normalizeRequired(request.trackingNumber());
        orderRepository.findByTrackingNumberIgnoreCase(trackingNumber)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new DuplicateTrackingNumberException(trackingNumber);
                });
        order.updateDetails(trackingNumber, normalizeRequired(request.customerName()), request.expectedAmount());
        return toResponse(order);
    }

    private Order find(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " was not found."));
    }

    private String normalizeRequired(String value) {
        return value.trim();
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(order.getId(), order.getTrackingNumber(), order.getCustomerName(),
                order.getExpectedAmount(), order.getStatus(), order.getCreatedAt(), order.getUpdatedAt());
    }
}
