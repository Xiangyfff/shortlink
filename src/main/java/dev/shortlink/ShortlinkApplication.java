package dev.shortlink;

import dev.shortlink.config.ShortlinkProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(ShortlinkProperties.class)
@EnableScheduling
public class ShortlinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortlinkApplication.class, args);
    }
}
