package com.demo.WechatBot.lifecycle;

import com.demo.WechatBot.service.AdminUserService;
import com.demo.WechatBot.service.AdminUserStore;
import com.demo.WechatBot.service.BotAccountStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 创建首个管理员，并在机器人恢复前完成历史数据归属迁移。
 */
@Component
@Order(1)
public class BootstrapAdminRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final AdminUserStore userStore;
    private final AdminUserService userService;
    private final BotAccountStore accountStore;
    private final String adminUsername;
    private final String adminPassword;

    public BootstrapAdminRunner(
            AdminUserStore userStore,
            AdminUserService userService,
            BotAccountStore accountStore,
            @Value("${app.security.bootstrap-admin.username:admin}") String adminUsername,
            @Value("${app.security.bootstrap-admin.password:}") String adminPassword) {
        this.userStore = userStore;
        this.userService = userService;
        this.accountStore = accountStore;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (userStore.findAnyAdmin().isEmpty()) {
            String password = adminPassword;
            boolean generated = password == null || password.isBlank();
            if (generated) {
                password = UUID.randomUUID().toString().replace("-", "");
            }
            AdminUserStore.AdminUser admin = userService.createUser(
                    adminUsername, password, AdminUserStore.ROLE_ADMIN);
            if (generated) {
                log.warn("已创建初始管理员 [{}]，一次性初始密码：{}。请登录后由管理员重置密码。",
                        admin.username(), password);
            } else {
                log.info("已创建初始管理员 [{}]", admin.username());
            }
        }

        AdminUserStore.AdminUser admin = userStore.findAnyAdmin().orElseThrow();
        int migrated = accountStore.assignUnownedBots(admin.id());
        if (migrated > 0) {
            log.info("已将 {} 个历史机器人归属到初始管理员 [{}]", migrated, admin.username());
        }
    }
}
