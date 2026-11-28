package com.example.attendance;

import com.example.attendance.common.AttendanceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneOffset;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AttendanceProperties.class)
public class AttendanceApplication {

    public static void main(String[] args) {
        // Keep JVM default timezone UTC so JDBC TIME columns do not shift relative to hibernate.jdbc.time_zone=UTC.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
        SpringApplication.run(AttendanceApplication.class, args);
    }
}
