package com.dublintech.mcpproto1.user;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory user cache keyed by username, seeded at startup by {@code SampleDataLoader}.
 */
@Repository
public class UserRepository {

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    public User save(User user) {
        User stored = user.id() == null || user.id() <= 0 ? user.withId(sequence.incrementAndGet()) : user;
        users.put(stored.username(), stored);
        return stored;
    }

    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(users.get(username));
    }

    public boolean deleteByUsername(String username) {
        return users.remove(username) != null;
    }

    public int count() {
        return users.size();
    }
}