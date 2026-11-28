package com.example.service.order.impl;

import com.example.service.order.OrderDraft;
import com.example.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/** 草稿内容不可变；确认绑定、订单与库存使用同一个 DataSource 事务。 */
@Repository
public class OrderDraftStore {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final JdbcTemplate jdbc;
    public OrderDraftStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Stored(OrderDraft draft, String fingerprint, String status, Long orderId, boolean expired) {}

    public LocalDateTime now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class); }

    public Stored insert(OrderDraft draft) {
        String fingerprint = fingerprint(draft);
        jdbc.update("INSERT INTO order_drafts(draft_id,user_id,snapshot_json,fingerprint,created_at,expires_at) VALUES (?,?,?,?,?,?)",
                draft.draftId(), draft.userId(), encode(draft), fingerprint, draft.createdAt(), draft.expiresAt());
        return new Stored(draft, fingerprint, "PREVIEWED", null, false);
    }

    public Stored load(Long user, String id, boolean forUpdate) {
        if (user == null || user <= 0) throw new UnauthorizedException("未登录或登录已失效");
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new BusinessException("订单预览ID格式不正确");
        if (forUpdate) requireTransaction();
        var rows = jdbc.query("SELECT *, expires_at<=CURRENT_TIMESTAMP(6) AS expired FROM order_drafts WHERE user_id=? AND draft_id=?" + (forUpdate ? " FOR UPDATE" : ""),
                (rs, n) -> {
                    OrderDraft draft = decode(rs.getString("snapshot_json"));
                    String fingerprint = rs.getString("fingerprint");
                    if (!user.equals(draft.userId()) || !id.equals(draft.draftId()) || !fingerprint.equals(fingerprint(draft))) {
                        throw new ConflictException("订单预览校验失败，请重新预览");
                    }
                    Number order = (Number) rs.getObject("order_id");
                    return new Stored(draft, fingerprint, rs.getString("status"), order == null ? null : order.longValue(), rs.getBoolean("expired"));
                }, user, id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("订单预览不存在或不属于当前用户，请重新预览");
        return rows.getFirst();
    }

    public void bind(Long user, String id, String key) {
        requireTransaction();
        try {
            if (jdbc.update("UPDATE order_drafts SET confirmation_key=? WHERE user_id=? AND draft_id=? AND status='PREVIEWED'", key, user, id) != 1)
                throw new ConflictException("订单预览状态已变化，请查询确认结果");
        } catch (DuplicateKeyException conflict) { throw new ConflictException("同一幂等键不能确认不同订单预览"); }
    }

    public void confirmed(Long user, String id, Long orderId) {
        requireTransaction();
        if (jdbc.update("UPDATE order_drafts SET status='CONFIRMED',order_id=?,confirmed_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND draft_id=? AND status='PREVIEWED'", orderId, user, id) != 1)
            throw new ConflictException("订单预览状态已变化，确认事务已回滚");
    }

    public void cancel(Long user, String id) {
        jdbc.update("UPDATE order_drafts SET status='CANCELLED' WHERE user_id=? AND draft_id=? AND status='PREVIEWED'", user, id);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("订单确认必须运行在数据库事务中");
    }

    private static String fingerprint(OrderDraft draft) {
        // 货币转为规范字符串，避免 MySQL JSON 对数字格式归一化后改变指纹。
        var items = draft.items().stream().map(i -> java.util.Arrays.asList(i.productId(), i.productTitle(), i.brand(), i.model(),
                i.previewUnitPrice().stripTrailingZeros().toPlainString(), i.quantity(), i.previewSubtotal().stripTrailingZeros().toPlainString())).toList();
        String canonical = encode(List.of(draft.draftId(), draft.userId(), draft.createdAt().toString(), draft.expiresAt().toString(),
                draft.version(), draft.source(), items, draft.previewTotalAmount().stripTrailingZeros().toPlainString()));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("无法保存订单预览", e); }
    }
    private static OrderDraft decode(String value) {
        try { return JSON.readValue(value, OrderDraft.class); }
        catch (Exception e) { throw new ConflictException("订单预览损坏，请重新预览"); }
    }
}
