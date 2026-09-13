package com.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模拟订单主表持久化实体。 */
@TableName("orders")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    /** 支付时限，订单创建时写入；超时后由关单任务关闭。 */
    private LocalDateTime expiresAt;
    /** 支付完成时间，仅已支付订单有值。 */
    private LocalDateTime paidAt;
    /** 关闭时间，仅已关闭订单有值。 */
    private LocalDateTime closedAt;
    /** 关闭原因，仅已关闭订单有值：USER_CANCELLED 或 PAYMENT_TIMEOUT。 */
    private String closeReason;
    private Integer status;
    private String idempotencyKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic(value = "0", delval = "1")
    private Integer deleted;
}
