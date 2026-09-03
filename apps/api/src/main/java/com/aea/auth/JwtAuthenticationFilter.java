package com.aea.auth;

import com.aea.platform.Principal;
import com.aea.platform.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a bearer token into a bound Principal for the duration of the request.
 *
 * SECURITY: this is the only place a tenant is established for an authenticated
 * request, and it reads it exclusively from the verified token. The clear() in
 * the finally block matters as much as the set(): the thread returns to a pool,
 * and a leaked tenant would show the next request another organisation's rows.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            jwt.verify(header.substring(BEARER.length())).ifPresent(this::bind);
        }

        // A tenant_id supplied in the request is never honoured -- the token is
        // the only source. Seeing one means either a confused client or an
        // attempt to address another tenant; both are worth recording.
        String claimedTenant = request.getParameter("tenant_id");
        if (claimedTenant != null) {
            log.warn("SECURITY: request supplied tenant_id={} in a parameter; ignored. path={}",
                    claimedTenant, request.getRequestURI());
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void bind(Principal principal) {
        TenantContext.set(principal);

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        principal.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        principal.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));

        var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
