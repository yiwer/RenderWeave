package cn.hbads.renderweave.system;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
final class SystemStatusController {

    private final JdbcClient jdbcClient;

    SystemStatusController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/status")
    SystemStatusResponse status() {
        Integer probe = jdbcClient.sql("select 1").query(Integer.class).single();
        if (probe != 1) {
            throw new IllegalStateException("PostgreSQL readiness probe returned an unexpected value");
        }
        return new SystemStatusResponse("renderweave-api", "ready", "ready", "0.14.0");
    }

    record SystemStatusResponse(
            String service,
            String status,
            String database,
            String contractVersion) {
    }
}
