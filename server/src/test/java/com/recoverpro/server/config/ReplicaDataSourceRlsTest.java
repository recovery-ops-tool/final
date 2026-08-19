package com.recoverpro.server.config;

import com.recoverpro.server.AbstractIntegrationTest;
import com.recoverpro.server.entity.Borrower;
import com.recoverpro.server.entity.Organization;
import com.recoverpro.server.repository.BorrowerRepository;
import com.recoverpro.server.security.RlsOrgIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM 02 TASK 2.4: the task's own words call this "the single most dangerous part" -- if the
 * replica-routed target were ever unwrapped RLS, this test would pass under the app's normal
 * DataSource but fail the moment {@link ReplicaRoutingContext#runOnReplica} is used, which is
 * exactly the failure mode to catch before it reaches anything real. Mirrors
 * {@link RlsIsolationTest}'s functional cross-org probe, run once through the ordinary (primary)
 * path and once explicitly routed to "replica".
 */
class ReplicaDataSourceRlsTest extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private BorrowerRepository borrowerRepository;

    private Borrower borrowerInOrgA;

    @AfterEach
    void clearRlsContext() {
        RlsOrgIdHolder.clear();
        if (borrowerInOrgA != null) {
            RlsOrgIdHolder.set(borrowerInOrgA.getOrganizationId());
            borrowerRepository.deleteById(borrowerInOrgA.getId());
            RlsOrgIdHolder.clear();
        }
    }

    @Test
    void dataSourceBean_isTheRoutingDataSource() {
        assertThat(dataSource).isInstanceOf(ReplicaRoutingDataSource.class);
    }

    @Test
    void replicaRoutedConnection_stillEnforcesOrgIsolation() {
        Organization orgA = createOrg("replica-rls-a");
        Organization orgB = createOrg("replica-rls-b");

        RlsOrgIdHolder.set(orgA.getId());
        borrowerInOrgA = borrowerRepository.save(
                Borrower.builder().organizationId(orgA.getId()).firstName("ReplicaProbe").build());

        // Ordinary (primary) path -- unchanged baseline, same assertions RlsIsolationTest makes.
        RlsOrgIdHolder.set(orgB.getId());
        assertThat(borrowerRepository.findById(borrowerInOrgA.getId())).isEmpty();
        RlsOrgIdHolder.set(orgA.getId());
        assertThat(borrowerRepository.findById(borrowerInOrgA.getId())).isPresent();

        // Explicitly replica-routed -- must enforce the exact same isolation, not bypass it.
        RlsOrgIdHolder.set(orgB.getId());
        Optional<Borrower> asOrgBViaReplica = ReplicaRoutingContext.runOnReplica(
                () -> borrowerRepository.findById(borrowerInOrgA.getId()));
        assertThat(asOrgBViaReplica)
                .as("org B must not see org A's row through the replica-routed connection either")
                .isEmpty();

        RlsOrgIdHolder.set(orgA.getId());
        Optional<Borrower> asOrgAViaReplica = ReplicaRoutingContext.runOnReplica(
                () -> borrowerRepository.findById(borrowerInOrgA.getId()));
        assertThat(asOrgAViaReplica)
                .as("org A must still see its own row through the replica-routed connection")
                .isPresent();
    }
}
