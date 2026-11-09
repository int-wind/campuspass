package com.campuspass.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campuspass.auth")
public class AuthProperties {
    private Duration accessTtl = Duration.ofMinutes(15);
    private Duration refreshTtl = Duration.ofDays(7);
    private String jwtSecret;
    private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();

    public Duration getAccessTtl() { return accessTtl; }
    public void setAccessTtl(Duration accessTtl) { this.accessTtl = accessTtl; }
    public Duration getRefreshTtl() { return refreshTtl; }
    public void setRefreshTtl(Duration refreshTtl) { this.refreshTtl = refreshTtl; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public BootstrapAdmin getBootstrapAdmin() { return bootstrapAdmin; }
    public void setBootstrapAdmin(BootstrapAdmin bootstrapAdmin) { this.bootstrapAdmin = bootstrapAdmin; }

    public static class BootstrapAdmin {
        private String username = "";
        private String password = "";
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
