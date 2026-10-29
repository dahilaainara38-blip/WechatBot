package com.demo.WechatBot.lifecycle;

import com.demo.WechatBot.service.BotInstanceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 应用启动后恢复所有已登录的微信机器人（免扫码）。
 * 新机器人通过管理页（http://localhost:8080）扫码添加。
 */
@Component
@Order(100)
public class BotApplicationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BotApplicationRunner.class);

    private final BotInstanceManager botManager;

    public BotApplicationRunner(BotInstanceManager botManager) {
        this.botManager = botManager;
    }

    @Override
    public void run(String... args) {
        log.info("==============================================");
        log.info("  WeChatBot 正在启动...");
        int restored = botManager.restoreAll();
        log.info("  已恢复 {} 个机器人实例", restored);
        if (restored == 0) {
            log.info("  暂无在线机器人，请访问管理页扫码添加：");
            log.info("  http://localhost:8080");
        }
        log.info("==============================================");
    }
}
