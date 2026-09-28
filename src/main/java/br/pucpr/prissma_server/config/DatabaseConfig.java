package br.pucpr.prissma_server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.net.URI;
import java.util.Arrays;
import java.util.stream.Collectors;

@Configuration
@Profile("!test")
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(@Value("${database.url}") String databaseUrl) {
        ParsedDatabaseUrl parsed = parse(databaseUrl);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(parsed.jdbcUrl());
        config.setUsername(parsed.username());
        config.setPassword(parsed.password());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);

        return new HikariDataSource(config);
    }

    static ParsedDatabaseUrl parse(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL must not be empty");
        }

        String normalized = databaseUrl.startsWith("jdbc:")
                ? databaseUrl.substring("jdbc:".length())
                : databaseUrl;

        URI uri = URI.create(normalized);

        if (!"postgresql".equals(uri.getScheme()) && !"postgres".equals(uri.getScheme())) {
            throw new IllegalArgumentException("DATABASE_URL must use the postgres or postgresql scheme");
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL must contain a PostgreSQL host");
        }

        String userInfo = uri.getUserInfo();
        if (userInfo == null || !userInfo.contains(":")) {
            throw new IllegalArgumentException("DATABASE_URL must contain username and password");
        }

        String[] credentials = userInfo.split(":", 2);
        String username = credentials[0];
        String password = credentials[1];

        String database = uri.getPath();
        if (database == null || database.length() <= 1) {
            throw new IllegalArgumentException("DATABASE_URL must contain a database name");
        }
        database = database.substring(1);

        int port = uri.getPort() == -1 ? 5432 : uri.getPort();

        String query = normalizeJdbcQuery(uri.getRawQuery());
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + "/" + database;
        if (!query.isBlank()) {
            jdbcUrl += "?" + query;
        }

        return new ParsedDatabaseUrl(jdbcUrl, username, password);
    }

    private static String normalizeJdbcQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }

        return Arrays.stream(rawQuery.split("&"))
                .map(parameter -> parameter.startsWith("channel_binding=")
                        ? "channelBinding=" + parameter.substring("channel_binding=".length())
                        : parameter)
                .collect(Collectors.joining("&"));
    }

    record ParsedDatabaseUrl(String jdbcUrl, String username, String password) {
    }
}
