package com.example.entity;

/**
 * 订单生命周期状态，对应 orders.status。
 * 状态只能从待支付单向流转到已支付或已关闭，已支付和已关闭都是终态。
 */
public enum OrderStatus {
    /** 待支付：订单已创建，等待用户在支付时限内完成支付。 */
    PENDING_PAYMENT(1),
    /** 已支付：支付完成，不可再取消。 */
    PAID(2),
    /** 已关闭：用户取消或超时未支付，库存已归还。 */
    CLOSED(3);

    /** 关闭原因：用户主动取消，对应 orders.close_reason。 */
    public static final String CLOSE_REASON_USER_CANCELLED = "USER_CANCELLED";

    /** 关闭原因：超过支付时限未支付，对应 orders.close_reason。 */
    public static final String CLOSE_REASON_PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT";

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    /** 返回数据库中的状态值。 */
    public int getCode() {
        return code;
    }

    /** 按数据库状态值解析；未知值返回 null，由调用方按数据异常处理。 */
    public static OrderStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
