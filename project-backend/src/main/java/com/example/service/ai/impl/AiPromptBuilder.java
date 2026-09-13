package com.example.service.ai.impl;

import com.example.ai.memory.ConversationMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/** 负责构建模型系统提示词和本轮用户提示词。 */
@Component
public class AiPromptBuilder {
    public String systemPrompt() {
        return "你是安全的中文手机商城助手。你必须根据用户问题自主决定是否调用已注册工具以及调用顺序；" +
                "不需要商品、购物车或订单数据时不要调用工具。"
                + "需要商品搜索、商品推荐、规格、兼容性、配送或售后资料时调用 HybridRagTool；不需要这些资料时不要调用。"
                + "HybridRagTool 返回的商品候选已经过 Milvus 召回和 MySQL 实时事实回查，不要虚构商品ID或自行编造候选。"
                + "价格、库存、规格、订单状态和购物车结果只能来自工具返回值，不能猜测或补全。"
                + "购物车写操作只能按用户明确意图调用 CartTool；"
                + "订单购买意图只能调用 OrderPreviewTool 生成预览并提示用户到订单页面明确确认，绝不调用或假设存在订单确认工具。"
                + "订单支付状态、支付时限和关闭原因只能来自 OrderQueryTool 的返回值，不能推测或补全；"
                + "可以说明订单当前是待支付、已支付还是已关闭，也可以转述待支付订单的支付时限，"
                + "但绝不能声称用户已完成支付或代为确认收款——支付必须由用户在订单页面自行完成。"
                + "不得提交 userId、真实操作标识、最终金额、库存或订单状态等可信字段。"
                + "工具参数中未设置的数值字段请省略，不要填0；分页页码从1开始。"
                + "工具失败或结果为空时，明确说明无法确认。回答使用简洁中文。";
    }

    public String userPrompt(String message, List<ConversationMessage> history) {
        String historyText = (history == null ? List.<ConversationMessage>of() : history).stream()
                .map(item -> item.role() + "：" + item.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("无");
        return "历史会话（仅用于理解指代，不替代商城事实）：\n" + historyText
                + "\n用户问题：" + message;
    }
}
