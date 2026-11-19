package com.bbcinema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BbCinemaApplication {

    public static void main(String[] args) {
        // Showtimes are local Vietnamese times; keep the JVM and JDBC in the same zone.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        SpringApplication.run(BbCinemaApplication.class, args);
    }
}
