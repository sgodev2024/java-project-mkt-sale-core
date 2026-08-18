package vn.coreplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

class LegacySeedDataCleanupTest extends AbstractApiTest {
  @Test void legacyOperationalPlaceholdersAreAbsent() {
    assertThat(jdbc.queryForObject("select count(*) from async.outbox_event where event_type='demo.pending.v1'", Long.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from async.job where job_type like 'demo.%'", Long.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from files.file_object where checksum_sha256 in (repeat('a',64),repeat('b',64),repeat('c',64),repeat('d',64))", Long.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from identity.role_summary where user_count in (3,8,2,184) and policy_count in (12,7,5,9)", Long.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from platform.module where metric in ('4 contracts','6 policies','12.4k records','12 pending','3 running','84.2 GB','24 definitions','Optional')", Long.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from platform.module where module_key in ('event-outbox','job-queue','file-management','local-identity') and status <> 'HEALTHY'", Long.class)).isZero();
  }

  @Test void coreResourceDescriptorsUseDatabaseCounts() {
    assertThat(jdbc.queryForObject("select count(*) from platform.resource_descriptor where resource_type in ('file-object','service-account')", Long.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select record_count from platform.resource_descriptor where resource_type='file-object'", Long.class))
        .isEqualTo(jdbc.queryForObject("select count(*) from files.file_object where status in ('ACTIVE','QUARANTINED')", Long.class));
    assertThat(jdbc.queryForObject("select record_count from platform.resource_descriptor where resource_type='service-account'", Long.class))
        .isEqualTo(jdbc.queryForObject("select count(*) from identity.account where account_type='SERVICE' and enabled", Long.class));
  }
}
