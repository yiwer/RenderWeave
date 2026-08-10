package cn.hbads.renderweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EnvironmentCanaryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void contractStatusReadsFromPostgresql() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("renderweave-api"))
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.database").value("ready"))
                .andExpect(jsonPath("$.contractVersion").value("0.9.0"));

        Integer appliedMigrations = jdbcClient
                .sql("select count(*) from flyway_schema_history where success = true")
                .query(Integer.class)
                .single();
        assertThat(appliedMigrations).isEqualTo(14);

        Integer capacityIndexes = jdbcClient.sql("""
                        select count(*)
                        from pg_indexes
                        where schemaname = 'public'
                          and indexname in (
                            'schema_draft_active_updated_desc_idx',
                            'schema_draft_active_updated_asc_idx',
                            'static_schema_origin_published_desc_idx',
                            'static_schema_origin_published_asc_idx',
                            'inference_run_network_claim_idx',
                            'inference_run_recent_idx'
                          )
                        """)
                .query(Integer.class)
                .single();
        assertThat(capacityIndexes).isEqualTo(6);

        Integer destructiveReservationLinks = jdbcClient
                .sql("""
                        select count(*)
                        from pg_constraint
                        where conname = 'inference_provider_reservation_run_id_fkey'
                        """)
                .query(Integer.class)
                .single();
        assertThat(destructiveReservationLinks).isZero();
    }
}
