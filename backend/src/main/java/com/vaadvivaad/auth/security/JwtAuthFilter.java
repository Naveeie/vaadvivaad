package com.vaadvivaad.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;

    public JwtAuthFilter(JwtService jwtService, UserDetailsServiceImpl userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. Extract Authorization header
        final String authHeader = request.getHeader("Authorization");

        // 2. If no Bearer token, skip this filter (let Spring Security handle it)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. Extract token (remove "Bearer " prefix)
        final String jwt = authHeader.substring(7);

        // 4. Extract username from token
        final String userEmail = jwtService.extractUsername(jwt);

        // 5. If username found and not already authenticated
        if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            // 6. Load user from database
            UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

            // 7. Validate token
            if (jwtService.isTokenValid(jwt, userDetails)) {

                // 8. Create authentication object
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,                           // credentials null (already authenticated)
                                userDetails.getAuthorities()
                        );

                // 9. Attach request details (IP, session info)
                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );

                // 10. Set in SecurityContext — Spring Security now trusts this request
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        // 11. Continue to next filter / controller
        filterChain.doFilter(request, response);
    }
}