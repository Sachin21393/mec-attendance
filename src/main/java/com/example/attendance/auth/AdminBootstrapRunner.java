package com.example.attendance.auth;

import com.example.attendance.common.AttendanceProperties;
import com.example.attendance.settings.AttendanceSettings;
import com.example.attendance.settings.AttendanceSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AttendanceProperties properties;
    private final AdminUserRepository adminUserRepository;
    private final AttendanceSettingsRepository settingsRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapRunner(
            AttendanceProperties properties,
            AdminUserRepository adminUserRepository,
            AttendanceSettingsRepository settingsRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.adminUserRepository = adminUserRepository;
        this.settingsRepository = settingsRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureDefaultSettings();

        AttendanceProperties.Bootstrap bootstrap = properties.getBootstrap();
        if (!bootstrap.isEnabled()) {
            return;
        }
        if (bootstrap.getAdminPassword() == null || bootstrap.getAdminPassword().isBlank()) {
            log.warn("Admin bootstrap enabled but ADMIN_BOOTSTRAP_PASSWORD is blank; skipping admin user creation.");
            return;
        }
        if (adminUserRepository.count() > 0) {
            return;
        }
        Instant now = Instant.now();
        AdminUser admin = new AdminUser(
                UUID.randomUUID(),
                bootstrap.getAdminUsername(),
                "System Administrator",
                passwordEncoder.encode(bootstrap.getAdminPassword()),
                AdminRole.TECH_ADMIN,
                now
        );
        adminUserRepository.save(admin);
        log.info("Bootstrapped initial admin user username={} role={}", admin.getUsername(), admin.getRole());
    }

    private void ensureDefaultSettings() {
        if (settingsRepository.count() > 0) {
            return;
        }
        Instant now = Instant.now();
        AttendanceSettings settings = new AttendanceSettings(
                UUID.randomUUID(),
                "MECATT Team",
                properties.getDefaults().getTimezone(),
                LocalTime.of(9, 0),
                15,
                LocalTime.of(18, 0),
                true,
                properties.getDefaults().getProxyCap(),
                true,
                properties.getBiometrics().getMatchThreshold(),
                "yunet-2023mar+sface-2021dec",
                now,
                null,
                now
        );
        settingsRepository.save(settings);
        log.info("Initialized default attendance settings timezone={}", settings.getTimezoneId());
    }
}
