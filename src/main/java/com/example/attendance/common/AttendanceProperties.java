package com.example.attendance.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "attendance")
public class AttendanceProperties {

    private Biometrics biometrics = new Biometrics();
    private Security security = new Security();
    private Defaults defaults = new Defaults();
    private Bootstrap bootstrap = new Bootstrap();

    public Biometrics getBiometrics() {
        return biometrics;
    }

    public void setBiometrics(Biometrics biometrics) {
        this.biometrics = biometrics;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    public static class Biometrics {
        private String provider = "mock";
        private boolean productionEnabled = false;
        private int embeddingDimension = 128;
        private double matchThreshold = 0.363;
        private int maxImageBytes = 5_242_880;
        private String encryptionKeyB64 = "";
        private String keyVersion = "v1";
        private String yunetModelPath = "models/face_detection_yunet_2023mar.onnx";
        private String sfaceModelPath = "models/face_recognition_sface_2021dec.onnx";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public boolean isProductionEnabled() {
            return productionEnabled;
        }

        public void setProductionEnabled(boolean productionEnabled) {
            this.productionEnabled = productionEnabled;
        }

        public int getEmbeddingDimension() {
            return embeddingDimension;
        }

        public void setEmbeddingDimension(int embeddingDimension) {
            this.embeddingDimension = embeddingDimension;
        }

        public double getMatchThreshold() {
            return matchThreshold;
        }

        public void setMatchThreshold(double matchThreshold) {
            this.matchThreshold = matchThreshold;
        }

        public int getMaxImageBytes() {
            return maxImageBytes;
        }

        public void setMaxImageBytes(int maxImageBytes) {
            this.maxImageBytes = maxImageBytes;
        }

        public String getEncryptionKeyB64() {
            return encryptionKeyB64;
        }

        public void setEncryptionKeyB64(String encryptionKeyB64) {
            this.encryptionKeyB64 = encryptionKeyB64;
        }

        public String getKeyVersion() {
            return keyVersion;
        }

        public void setKeyVersion(String keyVersion) {
            this.keyVersion = keyVersion;
        }

        public String getYunetModelPath() {
            return yunetModelPath;
        }

        public void setYunetModelPath(String yunetModelPath) {
            this.yunetModelPath = yunetModelPath;
        }

        public String getSfaceModelPath() {
            return sfaceModelPath;
        }

        public void setSfaceModelPath(String sfaceModelPath) {
            this.sfaceModelPath = sfaceModelPath;
        }
    }

    public static class Security {
        private boolean cookieSecure = false;
        private int workerSessionTtlDays = 30;
        private int verificationTokenTtlSeconds = 120;

        public boolean isCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }

        public int getWorkerSessionTtlDays() {
            return workerSessionTtlDays;
        }

        public void setWorkerSessionTtlDays(int workerSessionTtlDays) {
            this.workerSessionTtlDays = workerSessionTtlDays;
        }

        public int getVerificationTokenTtlSeconds() {
            return verificationTokenTtlSeconds;
        }

        public void setVerificationTokenTtlSeconds(int verificationTokenTtlSeconds) {
            this.verificationTokenTtlSeconds = verificationTokenTtlSeconds;
        }
    }

    public static class Defaults {
        private String timezone = "Asia/Kolkata";
        private int proxyCap = 1;

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public int getProxyCap() {
            return proxyCap;
        }

        public void setProxyCap(int proxyCap) {
            this.proxyCap = proxyCap;
        }
    }

    public static class Bootstrap {
        private boolean enabled = false;
        private String adminUsername = "admin";
        private String adminPassword = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAdminUsername() {
            return adminUsername;
        }

        public void setAdminUsername(String adminUsername) {
            this.adminUsername = adminUsername;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }
    }
}
