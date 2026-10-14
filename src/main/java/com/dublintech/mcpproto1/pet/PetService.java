package com.dublintech.mcpproto1.pet;

import com.dublintech.mcpproto1.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PetService {

    private final PetRepository petRepository;

    public PetService(PetRepository petRepository) {
        this.petRepository = petRepository;
    }

    public Pet addPet(Pet pet) {
        validate(pet);
        return petRepository.save(pet);
    }

    public Pet updatePet(Pet pet) {
        if (pet == null || pet.id() == null || pet.id() <= 0) {
            throw ApiException.badRequest("Invalid ID supplied");
        }
        requireExisting(pet.id());
        validate(pet);
        return petRepository.save(pet);
    }

    public Pet getPetById(long petId) {
        return requireExisting(petId);
    }

    public List<Pet> findByStatus(Collection<String> statusValues) {
        Set<PetStatus> statuses = statusValues.stream()
                .map(PetStatus::fromValue)
                .collect(Collectors.toSet());
        return sorted(petRepository.findAll().stream()
                .filter(pet -> statuses.contains(pet.status()))
                .toList());
    }

    public List<Pet> findByTags(Collection<String> tagNames) {
        Set<String> wanted = tagNames.stream().map(String::trim).collect(Collectors.toSet());
        if (wanted.isEmpty() || wanted.contains("")) {
            throw ApiException.badRequest("Invalid tag value");
        }
        return sorted(petRepository.findAll().stream()
                .filter(pet -> pet.tags() != null && pet.tags().stream()
                        .filter(Objects::nonNull)
                        .anyMatch(tag -> wanted.contains(tag.name())))
                .toList());
    }

    public Pet updatePetWithForm(long petId, String name, String status) {
        Pet pet = requireExisting(petId);
        if (name != null && !name.isBlank()) {
            pet = pet.withName(name);
        }
        if (status != null && !status.isBlank()) {
            try {
                pet = pet.withStatus(PetStatus.fromValue(status));
            } catch (ApiException ex) {
                throw ApiException.invalidInput(ex.getMessage());
            }
        }
        return petRepository.save(pet);
    }

    public void deletePet(long petId) {
        if (!petRepository.deleteById(petId)) {
            throw ApiException.notFound("Pet not found");
        }
    }

    public boolean exists(long petId) {
        return petRepository.existsById(petId);
    }

    /**
     * Counts pets per status; every status is present, with zero when no pets have it.
     */
    public Map<String, Integer> inventory() {
        Map<PetStatus, Integer> counts = new EnumMap<>(PetStatus.class);
        for (PetStatus status : PetStatus.values()) {
            counts.put(status, 0);
        }
        petRepository.findAll().stream()
                .map(Pet::status)
                .filter(Objects::nonNull)
                .forEach(status -> counts.merge(status, 1, Integer::sum));
        Map<String, Integer> result = new LinkedHashMap<>();
        counts.forEach((status, count) -> result.put(status.getValue(), count));
        return result;
    }

    private Pet requireExisting(long petId) {
        return petRepository.findById(petId)
                .orElseThrow(() -> ApiException.notFound("Pet not found"));
    }

    private static void validate(Pet pet) {
        if (pet == null || pet.name() == null || pet.name().isBlank()) {
            throw ApiException.invalidInput("Invalid input: 'name' is required");
        }
        if (pet.photoUrls() == null) {
            throw ApiException.invalidInput("Invalid input: 'photoUrls' is required");
        }
    }

    private static List<Pet> sorted(List<Pet> pets) {
        return pets.stream().sorted(Comparator.comparing(Pet::id)).toList();
    }
}