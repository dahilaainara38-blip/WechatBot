package com.demo.WechatBot.controller;

import com.demo.WechatBot.service.BotInstanceManager;
import com.demo.WechatBot.service.AdminUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 机器人管理接口。所有资源都按当前登录账号隔离。
 * <p>
 * 配套静态页面 src/main/resources/static/index.html。
 * </p>
 */
@RestController
@RequestMapping("/api/bots")
public class BotAdminController {

    private static final Logger log = LoggerFactory.getLogger(BotAdminController.class);

    private final BotInstanceManager botManager;

    public BotAdminController(BotInstanceManager botManager) {
        this.botManager = botManager;
    }

    /**
     * 创建登录槽位，返回二维码
     */
    @PostMapping("/login")
    public ResponseEntity<?> createLogin(@AuthenticationPrincipal AdminUserPrincipal principal) {
        try {
            BotInstanceManager.SlotView slot = botManager.createLoginSlot(principal.getId());
            return ResponseEntity.ok(slot);
        } catch (IllegalStateException e) {
            log.warn("创建登录槽位失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("创建登录槽位异常", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "创建登录失败: " + e.getMessage()));
        }
    }

    /**
     * 查询登录槽位状态（页面轮询）
     */
    @GetMapping("/login/{slotId}/status")
    public ResponseEntity<BotInstanceManager.SlotStatus> getLoginStatus(
            @PathVariable String slotId,
            @AuthenticationPrincipal AdminUserPrincipal principal) {
        BotInstanceManager.SlotStatus status = botManager.getSlotStatus(principal.getId(), slotId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * 机器人列表
     */
    @GetMapping
    public List<BotInstanceManager.BotView> listBots(
            @AuthenticationPrincipal AdminUserPrincipal principal) {
        return botManager.listBots(principal.getId());
    }

    /**
     * 下线并删除机器人
     */
    @PostMapping("/{botId}/remove")
    public ResponseEntity<?> removeBot(@PathVariable String botId,
                                       @AuthenticationPrincipal AdminUserPrincipal principal) {
        if (!botManager.removeBot(principal.getId(), botId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "机器人不存在"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
