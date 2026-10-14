package com.dublintech.mcpproto1.pet;

import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory pet cache, seeded at startup by {@code SampleDataLoader}.
 */
@Repository
public class PetRepository {

    private final Map<Long, Pet> pets = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public Pet save(Pet pet) {
        Pet stored = pet.id() == null || pet.id() <= 0 ? pet.withId(nextId()) : pet;
        pets.put(stored.id(), stored);
        return stored;
    }

    public Optional<Pet> findById(long id) {
        return Optional.ofNullable(pets.get(id));
    }

    public boolean existsById(long id) {
        return pets.containsKey(id);
    }

    public Collection<Pet> findAll() {
        return pets.values();
    }

    public boolean deleteById(long id) {
        return pets.remove(id) != null;
    }

    private long nextId() {
        long id;
        do {
            id = sequence.incrementAndGet();
        } while (pets.containsKey(id));
        return id;
    }
}