package br.pucpr.prissma_server.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConfigTest {

    @Test
    void parsesNeonConnectionStringIntoJdbcConfiguration() {
        var parsed = DatabaseConfig.parse(
                "postgresql://neondb_owner:secret@ep-example-pooler.sa-east-1.aws.neon.tech/prissma" +
                        "?sslmode=require&channel_binding=require"
        );

        assertThat(parsed.username()).isEqualTo("neondb_owner");
        assertThat(parsed.password()).isEqualTo("secret");
        assertThat(parsed.jdbcUrl()).isEqualTo(
                "jdbc:postgresql://ep-example-pooler.sa-east-1.aws.neon.tech:5432/prissma" +
                        "?sslmode=require&channelBinding=require"
        );
    }

    @Test
    void acceptsJdbcPrefixToo() {
        var parsed = DatabaseConfig.parse(
                "jdbc:postgresql://prissma:prissma@localhost:5432/prissma"
        );

        assertThat(parsed.username()).isEqualTo("prissma");
        assertThat(parsed.password()).isEqualTo("prissma");
        assertThat(parsed.jdbcUrl()).isEqualTo(
                "jdbc:postgresql://localhost:5432/prissma"
        );
    }
}
