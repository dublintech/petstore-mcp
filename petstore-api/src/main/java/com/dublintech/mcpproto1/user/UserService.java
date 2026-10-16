package com.dublintech.mcpproto1.user;

import com.dublintech.mcpproto1.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User createUser(User user) {
        requireUsername(user);
        return userRepository.save(user);
    }

    public List<User> createUsers(List<User> users) {
        users.forEach(UserService::requireUsername);
        return users.stream().map(userRepository::save).toList();
    }

    public User getUserByName(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    /** Replaces the user; a different username in the body renames them. */
    public User updateUser(String username, User user) {
        User existing = getUserByName(username);
        requireUsername(user);
        if (!user.username().equals(username)) {
            if (userRepository.findByUsername(user.username()).isPresent()) {
                throw ApiException.badRequest("Invalid user supplied: username already taken");
            }
            userRepository.deleteByUsername(username);
        }
        return userRepository.save(user.withId(existing.id()));
    }

    public void deleteUser(String username) {
        if (!userRepository.deleteByUsername(username)) {
            throw ApiException.notFound("User not found");
        }
    }

    /** Checks credentials; there is no real session, so login only validates them. */
    public void login(String username, String password) {
        boolean valid = userRepository.findByUsername(username)
                .map(user -> Objects.equals(user.password(), password))
                .orElse(false);
        if (!valid) {
            throw ApiException.badRequest("Invalid username/password supplied");
        }
    }

    private static void requireUsername(User user) {
        if (user == null || user.username() == null || user.username().isBlank()) {
            throw ApiException.badRequest("Invalid user supplied: 'username' is required");
        }
    }
}