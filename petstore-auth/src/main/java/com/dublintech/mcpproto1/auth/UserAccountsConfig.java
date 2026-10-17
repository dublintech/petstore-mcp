package com.dublintech.mcpproto1.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * The accounts people log in with. Only the authorization server ever sees a password: everything downstream
 * (mcp-remote, the gateway, the API) works with tokens instead.
 *
 * <p>These mirror the API's sample users so the two line up later. They are hashed with BCrypt at startup; a real
 * system would load already-hashed passwords from a user store and never keep plain-text passwords in code.
 */
@Configuration
public class UserAccountsConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        // Stores hashes as "{bcrypt}$2a$..." so the algorithm can be changed later without breaking old hashes.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(PasswordEncoder passwordEncoder) {
        return new InMemoryUserDetailsManager(
                user("user1", "password1", passwordEncoder),
                user("user2", "password2", passwordEncoder),
                user("user3", "password3", passwordEncoder));
    }

    private static org.springframework.security.core.userdetails.UserDetails user(String username, String password,
                                                                                  PasswordEncoder encoder) {
        return User.withUsername(username).password(encoder.encode(password)).roles("USER").build();
    }
}
