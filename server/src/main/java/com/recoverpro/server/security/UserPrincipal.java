package com.recoverpro.server.security;

import com.recoverpro.server.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
public class UserPrincipal implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final boolean enabled;
    private final boolean accountLocked;
    private final UUID organizationId;
    private final boolean organizationActive;
    private final Collection<? extends GrantedAuthority> authorities;

    /** Defaults organizationActive to true -- callers that build a UserPrincipal outside the
     *  request-authentication path (token minting, tests) have no suspension concern of their
     *  own; {@link com.recoverpro.server.security.CustomUserDetailsService}, the one path that
     *  actually gates access on it, uses the other constructor. */
    public UserPrincipal(User user) {
        this(user, true);
    }

    public UserPrincipal(User user, boolean organizationActive) {
        this.id                 = user.getId();
        this.email              = user.getEmail();
        this.passwordHash       = user.getPasswordHash();
        this.enabled            = user.isEnabled();
        this.accountLocked      = user.isCurrentlyLocked();
        this.organizationId     = user.getOrganizationId();
        this.organizationActive = organizationActive;
        this.authorities        = buildAuthorities(user);
    }

    private static Set<GrantedAuthority> buildAuthorities(User user) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        user.getRoles().forEach(role -> {
            authorities.add(new SimpleGrantedAuthority(role.getName()));
            role.getPermissions().forEach(permission ->
                    authorities.add(new SimpleGrantedAuthority(permission.getName())));
        });

        if (user.getDirectPermissions() != null) {
            user.getDirectPermissions().forEach(userPermission -> {
                if (userPermission.isValid()) {
                    if (userPermission.isGranted()) {
                        authorities.add(new SimpleGrantedAuthority(userPermission.getPermission().getName()));
                    } else {
                        authorities.removeIf(a -> a.getAuthority().equals(userPermission.getPermission().getName()));
                    }
                }
            });
        }

        return authorities;
    }

    @Override public String getUsername()                { return email; }
    @Override public String getPassword()                { return passwordHash; }
    @Override public boolean isEnabled()                 { return enabled; }
    @Override public boolean isAccountNonLocked()        { return !accountLocked; }
    @Override public boolean isAccountNonExpired()       { return true; }
    @Override public boolean isCredentialsNonExpired()   { return true; }
}
