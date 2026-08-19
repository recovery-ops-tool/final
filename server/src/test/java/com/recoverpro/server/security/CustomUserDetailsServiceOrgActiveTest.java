package com.recoverpro.server.security;

import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.entity.User;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** SYSTEM 18 TASK 18.2.b: the organizationActive flag CustomUserDetailsService computes is what
 *  JwtAuthenticationFilter gates access on -- these are the four states that matter. */
@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceOrgActiveTest {

    @Mock private UserRepository userRepository;
    @Mock private OrganizationRepository organizationRepository;

    private CustomUserDetailsService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new CustomUserDetailsService(userRepository, organizationRepository);
    }

    @Test
    void platformAdmin_noOrganization_alwaysActive() {
        User user = User.builder().id(UUID.randomUUID()).email("admin@platform.com")
                .organizationId(null).enabled(true).build();
        when(userRepository.findByEmail("admin@platform.com")).thenReturn(Optional.of(user));

        UserPrincipal principal = (UserPrincipal) service.loadUserByUsername("admin@platform.com");

        assertThat(principal.isOrganizationActive()).isTrue();
    }

    @Test
    void activeOrg_organizationActiveTrue() {
        UUID orgId = UUID.randomUUID();
        User user = User.builder().id(UUID.randomUUID()).email("agent@example.com")
                .organizationId(orgId).enabled(true).build();
        Organization org = Organization.builder().id(orgId).isActive(true).build();
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        UserPrincipal principal = (UserPrincipal) service.loadUserByUsername("agent@example.com");

        assertThat(principal.isOrganizationActive()).isTrue();
    }

    @Test
    void suspendedOrg_organizationActiveFalse() {
        UUID orgId = UUID.randomUUID();
        User user = User.builder().id(UUID.randomUUID()).email("agent@example.com")
                .organizationId(orgId).enabled(true).build();
        Organization org = Organization.builder().id(orgId).isActive(false).build();
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        UserPrincipal principal = (UserPrincipal) service.loadUserByUsername("agent@example.com");

        assertThat(principal.isOrganizationActive()).isFalse();
    }

    @Test
    void deletedOrg_organizationActiveFalseEvenIfIsActiveFlagStillTrue() {
        UUID orgId = UUID.randomUUID();
        User user = User.builder().id(UUID.randomUUID()).email("agent@example.com")
                .organizationId(orgId).enabled(true).build();
        Organization org = Organization.builder().id(orgId).isActive(true).deletedAt(Instant.now()).build();
        when(userRepository.findByEmail("agent@example.com")).thenReturn(Optional.of(user));
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        UserPrincipal principal = (UserPrincipal) service.loadUserByUsername("agent@example.com");

        assertThat(principal.isOrganizationActive()).isFalse();
    }
}
