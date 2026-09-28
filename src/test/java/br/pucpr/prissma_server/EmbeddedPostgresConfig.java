package br.pucpr.prissma_server;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * PostgreSQL real para os testes de integração, SEM Docker.
 *
 * Por que não Testcontainers: exige um daemon Docker, que não existe em todas
 * as máquinas de desenvolvimento (em Windows corporativo, o Docker Desktop
 * depende de WSL2 e de licença). Quando o daemon falta, o container estático
 * estourava no <clinit> e derrubava a suíte inteira com
 * "Could not find a valid Docker environment".
 *
 * Por que não H2: MODE=PostgreSQL nunca conseguiu rodar as migrations reais
 * (V2 falha em TIMESTAMPTZ; V6/V11/V12 fazem DROP CONSTRAINT com nomes gerados
 * pelo Postgres; V13+ usa índices únicos parciais e backfill com DISTINCT ON).
 *
 * O zonky embedded-postgres baixa os binários oficiais do PostgreSQL como
 * artefato Maven e sobe o servidor como processo nativo. É o mesmo motor que
 * roda em produção, então a suíte valida exatamente as migrations que o Neon
 * vai executar.
 *
 * A instância é ESTÁTICA e compartilhada: um único servidor para a suíte
 * inteira, não um por classe @SpringBootTest. O próprio zonky registra um
 * shutdown hook que mata o processo e limpa o diretório de dados no fim da JVM.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmbeddedPostgresConfig {

    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder()
                    // Durabilidade é irrelevante num banco descartável, e desligá-la
                    // corta boa parte do I/O das migrations e dos inserts de setup.
                    .setServerConfig("fsync", "off")
                    .setServerConfig("synchronous_commit", "off")
                    .setServerConfig("full_page_writes", "off")
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Falha ao iniciar o PostgreSQL embarcado para os testes", e);
        }
    }

    /**
     * Substitui o DataSource da aplicação. O DatabaseConfig de produção é
     * @Profile("!test"), então não há conflito de beans aqui.
     *
     * destroyMethod = "" porque o DataSource pertence ao servidor estático e é
     * reaproveitado por todos os contextos Spring da suíte: deixar o Spring
     * fechá-lo ao descartar o primeiro contexto quebraria os testes seguintes.
     */
    @Bean(destroyMethod = "")
    DataSource dataSource() {
        return POSTGRES.getPostgresDatabase();
    }
}

