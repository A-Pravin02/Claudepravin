package com.aea.api.me;

import com.aea.platform.ApiException;
import com.aea.platform.Principal;
import com.aea.platform.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Who the caller is, as the server sees them. The web shell renders this so a
 * user can see the roles and permissions actually in force -- which is also the
 * fastest way to spot an authorization bug during development.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    @GetMapping
    public MeResponse me() {
        Principal p = TenantContext.principal().orElseThrow(ApiException::forbidden);
        return new MeResponse(p.userId(), p.tenantId(),
                new TreeSet<>(p.roles()), new TreeSet<>(p.permissions()));
    }

    public record MeResponse(UUID userId, UUID tenantId,
                             Set<String> roles, Set<String> permissions) {}
}
