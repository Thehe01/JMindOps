package com.kama.jmindops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(name = "app.tools.database.enabled", havingValue = "true")
public class DatabaseToolDataSourceConfig {

    @Bean("databaseToolJdbcTemplate")
    public JdbcTemplate databaseToolJdbcTemplate(
            @Value("${app.tools.database.jdbc-url:}") String jdbcUrl,
            @Value("${app.tools.database.username:jmindops_tool_reader}") String username,
            @Value("${app.tools.database.password:}") String password
    ) {
        if (!StringUtils.hasText(jdbcUrl) || !StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new IllegalStateException(
                    "DATABASE_TOOL_JDBC_URL, DATABASE_TOOL_USERNAME and DATABASE_TOOL_PASSWORD "
                            + "are required when the database tool is enabled");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return new JdbcTemplate(dataSource);
    }
}
