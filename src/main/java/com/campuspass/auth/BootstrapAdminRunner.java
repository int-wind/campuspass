package com.campuspass.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminRunner implements ApplicationRunner {
    private final AuthProperties properties;
    private final AccountMapper accounts;
    private final PasswordEncoder encoder;

    public BootstrapAdminRunner(AuthProperties properties, AccountMapper accounts, PasswordEncoder encoder) {
        this.properties = properties;
        this.accounts = accounts;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = properties.getBootstrapAdmin().getUsername();
        String password = properties.getBootstrapAdmin().getPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) return;
        if (accounts.selectCount(new LambdaQueryWrapper<Account>().eq(Account::getUsername, username)) > 0) return;
        Account admin = new Account();
        admin.setUsername(username);
        admin.setPasswordHash(encoder.encode(password));
        admin.setDisplayName("CampusPass Admin");
        admin.setRole("ADMIN");
        admin.setStatus("ACTIVE");
        accounts.insert(admin);
    }
}
