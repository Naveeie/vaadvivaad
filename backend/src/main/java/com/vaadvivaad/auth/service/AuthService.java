package com.vaadvivaad.auth.service;

import com.vaadvivaad.auth.dto.AuthResponse;
import com.vaadvivaad.auth.dto.LoginRequest;
import com.vaadvivaad.auth.dto.RegisterRequest;
import com.vaadvivaad.auth.security.JwtService;
import com.vaadvivaad.common.exception.ResourceNotFoundException;
import com.vaadvivaad.user.entity.Role;
import com.vaadvivaad.user.entity.User;
import com.vaadvivaad.user.repository.UserRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthenticationManager authenticationManager
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException(
                    "Email already registered: " + request.email());
        }

        Role role = Role.USER;
        if (request.role() != null) {
            try {
                role = Role.valueOf(request.role().toUpperCase());
            } catch (IllegalArgumentException e) {
                role = Role.USER;
            }
        }

        User user = new User();
        user.setFullName(request.fullName());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPhoneNumber(request.phoneNumber());
        user.setRole(role);

        User saved = userRepository.save(user);

        String token = jwtService.generateToken(buildUserDetails(saved));

        return new AuthResponse(
                token,
                saved.getEmail(),
                saved.getRole().name(),
                "Registration successful"
        );
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {

        // Throws BadCredentialsException if email/password don't match
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.email(),
                        request.password()
                )
        );

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User", "email", request.email()));

        String token = jwtService.generateToken(buildUserDetails(user));

        return new AuthResponse(
                token,
                user.getEmail(),
                user.getRole().name(),
                "Login successful"
        );
    }

    // ─── Private Helper ─────────────────────────────────────────────────

    private UserDetails buildUserDetails(User user) {
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }
}