package com.dublintech.mcpproto1.sampledata;

import com.dublintech.mcpproto1.pet.Pet;
import com.dublintech.mcpproto1.pet.PetRepository;
import com.dublintech.mcpproto1.store.Order;
import com.dublintech.mcpproto1.store.OrderRepository;
import com.dublintech.mcpproto1.user.User;
import com.dublintech.mcpproto1.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Seeds the in-memory caches from the JSON files in {@code src/main/resources/sample-data/} at startup.
 */
@Component
public class SampleDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataLoader.class);

    private final JsonMapper jsonMapper;
    private final PetRepository petRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public SampleDataLoader(JsonMapper jsonMapper, PetRepository petRepository,
                            OrderRepository orderRepository, UserRepository userRepository) {
        this.jsonMapper = jsonMapper;
        this.petRepository = petRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Pet> pets = read("sample-data/pets.json", new TypeReference<>() {});
        List<Order> orders = read("sample-data/orders.json", new TypeReference<>() {});
        List<User> users = read("sample-data/users.json", new TypeReference<>() {});

        pets.forEach(petRepository::save);
        orders.forEach(order -> orderRepository.save(order.withDefaults()));
        users.forEach(userRepository::save);

        log.info("Loaded sample data: {} pets, {} orders, {} users", pets.size(), orders.size(), users.size());
    }

    private <T> List<T> read(String path, TypeReference<List<T>> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return jsonMapper.readValue(in, type);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not load sample data from " + path, ex);
        }
    }
}