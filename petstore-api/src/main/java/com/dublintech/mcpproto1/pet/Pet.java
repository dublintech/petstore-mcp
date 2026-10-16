package com.dublintech.mcpproto1.pet;

import java.util.List;

public record Pet(
        Long id,
        Category category,
        String name,
        List<String> photoUrls,
        List<Tag> tags,
        PetStatus status) {

    public Pet withId(Long newId) {
        return new Pet(newId, category, name, photoUrls, tags, status);
    }

    public Pet withName(String newName) {
        return new Pet(id, category, newName, photoUrls, tags, status);
    }

    public Pet withStatus(PetStatus newStatus) {
        return new Pet(id, category, name, photoUrls, tags, newStatus);
    }
}