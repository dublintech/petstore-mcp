package com.dublintech.mcpproto1.store;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory order cache, seeded at startup by {@code SampleDataLoader}. Each order is kept with its owner, which is
 * not part of the {@link Order} itself, so it can never be read from or written by a request body.
 */
@Repository
public class OrderRepository {

    /** An order and the user it belongs to: the {@code sub} of the token that placed it. */
    public record OwnedOrder(String owner, Order order) {
    }

    private final Map<Long, OwnedOrder> orders = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public Order save(Order order, String owner) {
        Objects.requireNonNull(owner, "owner");
        Order stored = order.id() == null || order.id() <= 0 ? order.withId(nextId()) : order;
        orders.put(stored.id(), new OwnedOrder(owner, stored));
        return stored;
    }

    public Optional<OwnedOrder> findById(long id) {
        return Optional.ofNullable(orders.get(id));
    }

    /** The owner's orders, oldest first. */
    public List<Order> findByOwner(String owner) {
        return orders.values().stream()
                .filter(owned -> owned.owner().equals(owner))
                .map(OwnedOrder::order)
                .sorted(Comparator.comparing(Order::id))
                .toList();
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
