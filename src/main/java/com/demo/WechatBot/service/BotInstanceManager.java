package com.demo.WechatBot.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnHeartbeatListener;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 机器人实例池。
 * <p>
 * 管理所有微信机器人实例的生命周期：扫码登录槽位、登录态持久化、
 * 启动恢复、心跳掉线检测、消息发送路由。
 * </p>
 * <p>
 * 登录槽位（LoginSlot）是"一张等待被扫的二维码"，只存在于内存；
 * 扫码确认后提升为正式 BotInstance 并持久化到 bot_accounts 表。
 * </p>
 */
@Component
public class BotInstanceManager {

    private static final Logger log = LoggerFactory.getLogger(BotInstanceManager.class);

    /** 同时等待扫码的槽位上限，防止无限制创建客户端 */
    private static final int MAX_PENDING_SLOTS = 10;

    /** 心跳连续失败多少次判定为掉线 */
    private static final int HEARTBEAT_FAILURE_THRESHOLD = 3;

    /** 终态槽位保留时长（毫秒），超时后在下次创建槽位时被清理 */
    private static final long TERMINAL_SLOT_TTL_MS = 10 * 60 * 1000;

    /**
     * 登录槽位视图（给管理页用）
     */
    public record SlotView(String slotId, String qrType, String qrContent) {
    }

    /**
     * 槽位状态视图（给管理页轮询用）
     */
    public record SlotStatus(String status, String botId) {
    }

    /**
     * 机器人列表视图（给管理页用）
     */
    public record BotView(String botId, String userId, String status, String onlineSince) {
    }

    /**
     * 登录槽位：一个等待扫码的独立客户端
     */
    private static class LoginSlot {
        final String slotId;
        final String ownerId;
        final ILinkClient client;
        final String qrContent;
        final Instant createdAt = Instant.now();
        volatile String confirmedBotId;
        volatile boolean rejected;

        LoginSlot(String slotId, String ownerId, ILinkClient client, String qrContent) {
            this.slotId = slotId;
            this.ownerId = ownerId;
            this.client = client;
            this.qrContent = qrContent;
        }
    }

    private final AiChatService aiChatService;
    private final MultimodalService multimodalService;
    private final BotAccountStore accountStore;
    private final ConversationContextStore contextStore;
    private final ConversationDataCleanupService cleanupService;
    private final long loginTimeoutMs;
    private final long heartbeatIntervalMs;
    private final int maxBotsPerUser;
    private final int maxPendingSlotsPerUser;

    private final Map<String, LoginSlot> slots = new ConcurrentHashMap<>();
    private final Map<String, BotInstance> bots = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> heartbeatFailures = new ConcurrentHashMap<>();

    /** 维护任务线程（关闭客户端等可能阻塞的操作），避免占用 SDK 回调线程 */
    private final ExecutorService maintenance = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bot-manager");
        t.setDaemon(true);
        return t;
    });

    public BotInstanceManager(AiChatService aiChatService,
                              MultimodalService multimodalService,
                              BotAccountStore accountStore,
                              ConversationContextStore contextStore,
                              ConversationDataCleanupService cleanupService,
                              @Value("${ilink.login-timeout-ms:180000}") long loginTimeoutMs,
                              @Value("${ilink.heartbeat-interval-ms:30000}") long heartbeatIntervalMs,
                              @Value("${app.security.max-bots-per-user:3}") int maxBotsPerUser,
                              @Value("${app.security.max-pending-slots-per-user:2}") int maxPendingSlotsPerUser) {
        this.aiChatService = aiChatService;
        this.multimodalService = multimodalService;
        this.accountStore = accountStore;
        this.contextStore = contextStore;
        this.cleanupService = cleanupService;
        this.loginTimeoutMs = loginTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.maxBotsPerUser = maxBotsPerUser;
        this.maxPendingSlotsPerUser = maxPendingSlotsPerUser;
    }

    /**
     * 创建一个登录槽位：构建独立客户端并获取二维码。
     */
    public SlotView createLoginSlot(String ownerId) {
        evictTerminalSlots();
        long activeSlots = slots.values().stream().filter(slot -> !isTerminal(slot)).count();
        if (activeSlots >= MAX_PENDING_SLOTS) {
            throw new IllegalStateException("同时等待扫码的名额已满，请稍后再试");
        }
        long ownPendingSlots = slots.values().stream()
                .filter(slot -> slot.ownerId.equals(ownerId) && !isTerminal(slot)).count();
        if (ownPendingSlots >= maxPendingSlotsPerUser) {
            throw new IllegalStateException("当前账号等待扫码的名额已满，请先完成或关闭其他扫码");
        }
        if (accountStore.countByOwner(ownerId) >= maxBotsPerUser) {
            throw new IllegalStateException("当前账号的机器人数量已达上限");
        }

        String slotId = UUID.randomUUID().toString();
        ILinkClient client = buildClient(null, slotId);
        String qrContent;
        try {
            qrContent = client.executeLogin();
        } catch (Exception e) {
            maintenance.submit(client::close);
            throw new IllegalStateException("获取登录二维码失败: " + e.getMessage(), e);
        }

        slots.put(slotId, new LoginSlot(slotId, ownerId, client, qrContent));
        log.info("登录槽位已创建 [slot={}]", slotId);

        String qrType = (qrContent.startsWith("http://") || qrContent.startsWith("https://"))
                ? "url" : "base64";
        return new SlotView(slotId, qrType, qrContent);
    }

    /**
     * 查询槽位登录状态（管理页轮询用）。槽位不存在返回 null。
     */
    public SlotStatus getSlotStatus(String ownerId, String slotId) {
        LoginSlot slot = slots.get(slotId);
        if (slot == null || !slot.ownerId.equals(ownerId)) {
            return null;
        }
        if (slot.rejected) {
            return new SlotStatus("FAILED", null);
        }
        LoginStatus.Status s = slot.client.getLoginStatus().getStatus();
        return switch (s) {
            case WAITING, NOT_LOGIN -> new SlotStatus("WAITING", null);
            case SCANNED -> new SlotStatus("SCANNED", null);
            case LOGGED_IN -> new SlotStatus("CONFIRMED", slot.confirmedBotId);
            case EXPIRED -> new SlotStatus("EXPIRED", null);
            case ERROR -> new SlotStatus("FAILED", null);
        };
    }

    /**
     * 机器人列表：数据库记录 + 内存在线状态合并
     */
    public List<BotView> listBots(String ownerId) {
        List<BotView> result = new ArrayList<>();
        for (BotAccountStore.BotAccount acc : accountStore.findAllByOwner(ownerId)) {
            BotInstance inst = bots.get(acc.botId());
            boolean online = inst != null && inst.getClient().isLoggedIn();
            result.add(new BotView(
                    acc.botId(),
                    acc.userId(),
                    online ? BotAccountStore.STATUS_ONLINE : BotAccountStore.STATUS_OFFLINE,
                    online ? inst.getOnlineSince().toString() : acc.updatedAt()
            ));
        }
        return result;
    }

    /**
     * 下线并删除机器人：关闭客户端、清除登录态
     */
    public boolean removeBot(String ownerId, String botId) {
        BotAccountStore.BotAccount account = accountStore.findByBotId(botId).orElse(null);
        if (account == null || !ownerId.equals(account.ownerId())) {
            return false;
        }
        BotInstance inst = bots.remove(botId);
        heartbeatFailures.remove(botId);
        boolean deleted = cleanupService.deleteBotCompletely(botId, ownerId);
        if (inst != null) {
            maintenance.submit(inst::close);
        }
        if (deleted) {
            log.info("机器人已下线删除 [botId={}, ownerId={}]", botId, ownerId);
        }
        return deleted;
    }

    public boolean isOnline(String botId) {
        BotInstance inst = bots.get(botId);
        return inst != null && inst.getClient().isLoggedIn();
    }

    /**
     * 启动时恢复所有已登录机器人（免扫码）。
     * 单个恢复失败不影响其他，失败的标记为离线等待重新扫码。
     */
    public int restoreAll() {
        List<BotAccountStore.BotAccount> accounts = accountStore.findAll();
        int restored = 0;
        for (BotAccountStore.BotAccount acc : accounts) {
            if (!BotAccountStore.STATUS_ONLINE.equals(acc.status()) || bots.containsKey(acc.botId())) {
                continue;
            }
            try {
                LoginContext ctx = new LoginContext(
                        acc.botToken(), acc.userId(), acc.botId(), acc.baseUrl());
                ResumeContext.Builder rb = ResumeContext.builder(ctx);
                if (acc.updatesCursor() != null && !acc.updatesCursor().isBlank()) {
                    rb.updatesCursor(acc.updatesCursor());
                }
                rb.conversationContexts(contextStore.findByBotId(acc.botId()));
                ILinkClient client = buildClient(rb.build(), null);
                bots.put(acc.botId(), new BotInstance(
                        acc.botId(), client, aiChatService, multimodalService, contextStore));
                restored++;
                log.info("机器人已恢复上线 [botId={}, userId={}]", acc.botId(), acc.userId());
            } catch (Exception e) {
                log.error("机器人恢复失败，标记离线 [botId={}]", acc.botId(), e);
                accountStore.markOffline(acc.botId());
            }
        }
        return restored;
    }

    /**
     * 构建一个 ILinkClient，统一接线登录/消息/心跳监听。
     * 线程数压低：每实例 core=1/max=2/scheduler=1，20 个实例约 60-80 条线程。
     *
     * @param resumeContext 恢复上下文，新登录槽位传 null
     * @param slotId        所属登录槽位 ID，恢复场景传 null
     */
    private ILinkClient buildClient(ResumeContext resumeContext, String slotId) {
        ILinkConfig config = ILinkConfig.builder()
                .loginTimeoutMs(loginTimeoutMs)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(heartbeatIntervalMs)
                .ioCoreThreads(1)
                .ioMaxThreads(2)
                .schedulerThreads(1)
                .build();

        // 监听器在 build() 之前注册，但回调发生在 build() 之后，
        // 用 AtomicReference 让回调能拿到构建完成的 client
        AtomicReference<ILinkClient> self = new AtomicReference<>();

        ILinkClient client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        handleLoginSuccess(slotId, context, self.get());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.error("微信登录失败 [slot={}]: {}", slotId, throwable.getMessage());
                    }
                })
                .onMessage(messages -> {
                    ILinkClient src = self.get();
                    if (src == null) return;
                    LoginContext ctx = src.getLoginContext();
                    if (ctx == null) return;
                    BotInstance inst = bots.get(ctx.getBotId());
                    if (inst != null) {
                        inst.handleMessages(messages);
                    }
                })
                .onHeartbeat(new OnHeartbeatListener() {
                    @Override
                    public void onHeartbeatSuccess() {
                        ILinkClient src = self.get();
                        if (src == null || src.getLoginContext() == null) return;
                        AtomicInteger f = heartbeatFailures.get(src.getLoginContext().getBotId());
                        if (f != null) f.set(0);
                    }

                    @Override
                    public void onHeartbeatFailure(Throwable cause) {
                        ILinkClient src = self.get();
                        if (src == null || src.getLoginContext() == null) return;
                        handleHeartbeatFailure(src.getLoginContext().getBotId(), cause);
                    }
                })
                .resumeContext(resumeContext)
                .build();

        self.set(client);
        return client;
    }

    /**
     * 登录成功：槽位提升为正式实例，登录态落库
     */
    private void handleLoginSuccess(String slotId, LoginContext ctx, ILinkClient client) {
        log.info("=== 微信扫码登录成功 [botId={}, userId={}] ===", ctx.getBotId(), ctx.getUserId());

        if (client == null) {
            log.error("登录成功但客户端引用缺失 [botId={}]", ctx.getBotId());
            return;
        }

        LoginSlot slot = slotId != null ? slots.get(slotId) : null;
        if (slotId != null && slot == null) {
            log.warn("登录槽位已失效，丢弃本次登录 [botId={}, slot={}]", ctx.getBotId(), slotId);
            maintenance.submit(client::close);
            return;
        }

        BotAccountStore.BotAccount existing = accountStore.findByBotId(ctx.getBotId()).orElse(null);
        String ownerId = slot != null ? slot.ownerId : existing != null ? existing.ownerId() : null;
        if (ownerId == null || ownerId.isBlank()) {
            rejectLogin(slot, client, ctx.getBotId(), "无法确定机器人归属");
            return;
        }
        if (slot != null && existing != null && !ownerId.equals(existing.ownerId())) {
            rejectLogin(slot, client, ctx.getBotId(), "机器人已归属其他管理账号");
            return;
        }
        if (slot != null && existing == null && accountStore.countByOwner(ownerId) >= maxBotsPerUser) {
            rejectLogin(slot, client, ctx.getBotId(), "当前账号的机器人数量已达上限");
            return;
        }

        try {
            accountStore.upsertOnline(
                    ownerId, ctx.getBotId(), ctx.getUserId(), ctx.getBotToken(), ctx.getBaseUrl());
        } catch (Exception e) {
            log.error("保存机器人归属失败 [botId={}, ownerId={}]", ctx.getBotId(), ownerId, e);
            rejectLogin(slot, client, ctx.getBotId(), "保存机器人归属失败");
            return;
        }

        BotInstance inst = new BotInstance(
                ctx.getBotId(), client, aiChatService, multimodalService, contextStore);
        // 同一微信号重复登录时，关闭旧实例避免双客户端并行
        BotInstance old = bots.put(ctx.getBotId(), inst);
        if (old != null && old.getClient() != client) {
            log.warn("同一微信号重复登录，关闭旧实例 [botId={}]", ctx.getBotId());
            maintenance.submit(old::close);
        }

        if (slot != null) {
            slot.confirmedBotId = ctx.getBotId();
        }
    }

    private void rejectLogin(LoginSlot slot, ILinkClient client, String botId, String reason) {
        if (slot != null) {
            slot.rejected = true;
        }
        log.warn("拒绝机器人登录 [botId={}]: {}", botId, reason);
        maintenance.submit(client::close);
    }

    private boolean isTerminal(LoginSlot slot) {
        if (slot.rejected) {
            return true;
        }
        LoginStatus.Status status = slot.client.getLoginStatus().getStatus();
        return status == LoginStatus.Status.LOGGED_IN
                || status == LoginStatus.Status.EXPIRED
                || status == LoginStatus.Status.ERROR;
    }

    /**
     * 心跳失败：连续超过阈值判定掉线，关闭实例并标记离线
     */
    private void handleHeartbeatFailure(String botId, Throwable cause) {
        int count = heartbeatFailures
                .computeIfAbsent(botId, k -> new AtomicInteger())
                .incrementAndGet();
        log.warn("心跳失败 [botId={}] 第 {} 次: {}", botId, count, cause.getMessage());

        if (count >= HEARTBEAT_FAILURE_THRESHOLD) {
            log.error("心跳连续失败 {} 次，判定掉线 [botId={}]", count, botId);
            BotInstance inst = bots.remove(botId);
            heartbeatFailures.remove(botId);
            accountStore.markOffline(botId);
            if (inst != null) {
                maintenance.submit(inst::close);
            }
        }
    }

    /**
     * 清理终态（已确认/过期/失败）且过期的槽位，释放客户端资源
     */
    private void evictTerminalSlots() {
        Instant cutoff = Instant.now().minusMillis(TERMINAL_SLOT_TTL_MS);
        slots.entrySet().removeIf(e -> {
            LoginSlot slot = e.getValue();
            LoginStatus.Status s = slot.client.getLoginStatus().getStatus();
            if (isTerminal(slot) && slot.createdAt.isBefore(cutoff)) {
                // 正常确认的客户端归 BotInstance 管；被拒绝的客户端仍由槽位负责关闭
                if (s != LoginStatus.Status.LOGGED_IN || slot.rejected) {
                    maintenance.submit(slot.client::close);
                }
                return true;
            }
            return false;
        });
    }

    /**
     * 停机：持久化消息游标，关闭所有客户端
     */
    @PreDestroy
    public void shutdown() {
        log.info("正在关闭所有机器人实例...");
        for (BotInstance inst : bots.values()) {
            try {
                String cursor = inst.exportUpdatesCursor();
                if (cursor != null) {
                    accountStore.updateCursor(inst.getBotId(), cursor);
                }
            } catch (Exception e) {
                log.warn("保存消息游标失败 [botId={}]", inst.getBotId(), e);
            }
            inst.close();
        }
        bots.clear();
        for (LoginSlot slot : slots.values()) {
            // 已确认登录的客户端已在上面随 BotInstance 关闭
            if (slot.client.getLoginStatus().getStatus() != LoginStatus.Status.LOGGED_IN || slot.rejected) {
                try {
                    slot.client.close();
                } catch (Exception e) {
                    log.warn("关闭登录槽位失败 [slot={}]", slot.slotId, e);
                }
            }
        }
        slots.clear();
        maintenance.shutdown();
        log.info("所有机器人实例已关闭");
    }
}
