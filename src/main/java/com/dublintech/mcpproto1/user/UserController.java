package com.dublintech.mcpproto1.user;

import com.dublintech.mcpproto1.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping(path = "/v2/user", produces = MediaType.APPLICATION_JSON_VALUE)
public class UserController {

    private static final int RATE_LIMIT_PER_HOUR = 5000;

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse createUser(@RequestBody User user) {
        User created = userService.createUser(user);
        return ApiResponse.ok(String.valueOf(created.id()));
    }

    @PostMapping(path = "/createWithList", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse createUsersWithListInput(@RequestBody List<User> users) {
        userService.createUsers(users);
        return ApiResponse.ok("ok");
    }

    @PostMapping(path = "/createWithArray", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse createUsersWithArrayInput(@RequestBody List<User> users) {
        userService.createUsers(users);
        return ApiResponse.ok("ok");
    }

    // Mapped before /{username} so "login" and "logout" are never treated as usernames.
    @GetMapping("/login")
    public ResponseEntity<ApiResponse> loginUser(@RequestParam String username, @RequestParam String password) {
        userService.login(username, password);
        OffsetDateTime expiresAfter = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
        return ResponseEntity.ok()
                .header("X-Expires-After", expiresAfter.toString())
                .header("X-Rate-Limit", String.valueOf(RATE_LIMIT_PER_HOUR))
                .body(ApiResponse.ok("logged in user session:" + System.currentTimeMillis()));
    }

    @GetMapping("/logout")
    public ApiResponse logoutUser() {
        return ApiResponse.ok("ok");
    }

    @GetMapping("/{username}")
    public User getUserByName(@PathVariable String username) {
        return userService.getUserByName(username);
    }

    @PutMapping(path = "/{username}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse updateUser(@PathVariable String username, @RequestBody User user) {
        User updated = userService.updateUser(username, user);
        return ApiResponse.ok(String.valueOf(updated.id()));
    }

    @DeleteMapping("/{username}")
    public ApiResponse deleteUser(@PathVariable String username) {
        userService.deleteUser(username);
        return ApiResponse.ok(username);
    }
}