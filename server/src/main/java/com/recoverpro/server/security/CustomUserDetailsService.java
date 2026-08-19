package com.recoverpro.server.security;

import com.recoverpro.server.entity.User;
import com.recoverpro.server.repository.OrganizationRepository;
import com.recoverpro.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;

    // SYSTEM 18 TASK 18.2.b: the org's active flag rides the SAME cached entry as the rest of a
    // user's details (rather than a separate cache) specifically so that suspending an org can be
    // made to take effect "on the next request" through the one eviction path SYSTEM 15 already
    // established -- see PlatformOrganizationController.evictOrgUserCachesAfterCommit().
    @Override
    @Cacheable(value = "userDetails", key = "#email", sync = true)
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        boolean organizationActive = user.getOrganizationId() == null
                || organizationRepository.findById(user.getOrganizationId())
                        .map(org -> org.isActive() && org.getDeletedAt() == null)
                        .orElse(true);
        return new UserPrincipal(user, organizationActive);
    }

    @CacheEvict(value = "userDetails", key = "#email")
    public void evictUserCache(String email) {}
}
