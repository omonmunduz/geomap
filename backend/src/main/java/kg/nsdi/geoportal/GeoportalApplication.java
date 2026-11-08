package kg.nsdi.geoportal;

import kg.nsdi.geoportal.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(AppProperties.class)
public class GeoportalApplication {
    public static void main(String[] args) {
        SpringApplication.run(GeoportalApplication.class, args);
    }
}
