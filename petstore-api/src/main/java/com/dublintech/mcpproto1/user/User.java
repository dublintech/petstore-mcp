package com.dublintech.mcpproto1.user;

import com.fasterxml.jackson.annotation.JsonProperty;

public record User(
        Long id,
        String username,
        String firstName,
        String lastName,
        String email,
        // Accepted on input but never echoed back in responses.
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
        String phone,
        Integer userStatus) {

    public User withId(Long newId) {
        return new User(newId, username, firstName, lastName, email, password, phone, userStatus);
    }
}