package com.xyblue.itemnest.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfig {
    @Bean
    Path itemNestDataDir(@Value("${itemnest.data-dir:../data}") String configuredPath) throws IOException {
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }

    @Bean
    DataSource dataSource(Path itemNestDataDir) {
        Path dbPath = itemNestDataDir.resolve("inventory.db");
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        return dataSource;
    }
}
