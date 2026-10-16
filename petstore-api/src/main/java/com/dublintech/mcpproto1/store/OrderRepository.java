package com.dublintech.mcpproto1.store;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory order cache, seeded at startup by {@code SampleDataLoader}.
 */
@Repository
public class OrderRepository {

    private final Map<Long, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public Order save(Order order) {
        Order stored = order.id() == null || order.id() <= 0 ? order.withId(nextId()) : order;
        orders.put(stored.id(), stored);
        return stored;
    }

    public Optional<Order> findById(long id) {
        return Optional.ofNullable(orders.get(id));
    }

    public boolean deleteById(long id) {
        return orders.remove(id) != null;
    }

    public int count() {
        return orders.size();
    }

    private long nextId() {
        long id;
        do {
            id = sequence.incrementAndGet();
        } while (orders.containsKey(id));
        return id;
    }
}