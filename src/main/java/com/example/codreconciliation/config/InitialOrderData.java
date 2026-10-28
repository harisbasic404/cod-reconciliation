package com.example.codreconciliation.config;

import java.math.BigDecimal;
import java.util.List;

import com.example.codreconciliation.entity.Order;
import com.example.codreconciliation.enums.OrderStatus;
import com.example.codreconciliation.repository.OrderRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InitialOrderData implements CommandLineRunner {

    private final OrderRepository orderRepository;

    public InitialOrderData(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (orderRepository.count() > 0) {
            return;
        }
        orderRepository.saveAll(List.of(
                new Order("TRK-1001", "Marko Markovic", new BigDecimal("149.90"), OrderStatus.PENDING),
                new Order("TRK-1002", "Ana Anic", new BigDecimal("75.00"), OrderStatus.PENDING),
                new Order("TRK-1003", "Emir Kovac", new BigDecimal("39.99"), OrderStatus.PENDING),
                new Order("TRK-1004", "Sara Saric", new BigDecimal("120.50"), OrderStatus.PENDING),
                new Order("TRK-1005", "Ivan Ivanovic", new BigDecimal("55.00"), OrderStatus.PENDING)
        ));
    }
}
