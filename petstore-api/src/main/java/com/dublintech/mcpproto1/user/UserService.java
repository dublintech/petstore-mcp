package com.dublintech.mcpproto1.user;

import com.dublintech.mcpproto1.common.ApiException;
import com.dublintech.mcpproto1.common.ErrorCode;
import com.dublintech.mcpproto1.common.FieldError;
import com.dublintech.mcpproto1.common.FieldError.Rule;
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
                .orElseThrow(() -> ApiException.notFound(ErrorCode.USER_NOT_FOUND, "User not found"));
    }

    /** Replaces the user; a different username in the body renames them. */
    public User updateUser(String username, User user) {
        User existing = getUserByName(username);
        requireUsername(user);
        if (!user.username().equals(username)) {
            if (userRepository.findByUsername(user.username()).isPresent()) {
                throw ApiException.badRequest(ErrorCode.USERNAME_TAKEN, "Invalid user supplied: username already taken", FieldError.of("username", Rule.UNIQUE));
            }
            userRepository.deleteByUsername(username);
        }
        return userRepository.save(user.withId(existing.id()));
    }

    public void deleteUser(String username) {
        if (!userRepository.deleteByUsername(username)) {
            throw ApiException.notFound(ErrorCode.USER_NOT_FOUND, "User not found");
        }
    }

    /** Checks credentials; there is no real session, so login only validates them. */
    public void login(String username, String password) {
        boolean valid = userRepository.findByUsername(username)
                .map(user -> Objects.equals(user.password(), password))
                .orElse(false);
        if (!valid) {
            throw ApiException.badRequest(ErrorCode.INVALID_CREDENTIALS, "Invalid username/password supplied");
        }
    }

    private static void requireUsername(User user) {
        if (user == null || user.username() == null || user.username().isBlank()) {
            throw ApiException.badRequest(ErrorCode.INVALID_USER, "Invalid user supplied: 'username' is required", FieldError.of("username", Rule.REQUIRED));
        }
    }
}