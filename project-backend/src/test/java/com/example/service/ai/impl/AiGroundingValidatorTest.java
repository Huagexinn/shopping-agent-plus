
package com.example.service.ai.impl;

import com.example.ai.rag.KnowledgeReference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class AiGroundingValidatorTest {


    @Test
    void shouldAcceptCorrectProductFacts() {

        List<KnowledgeReference> references = mockProductReferences();

        var result = AiGroundingValidator.validate(
                "商品ID 1 的价格为5999元，库存剩余10件",
                "请查询商品ID 1",
                List.of(),
                references
        );

        System.out.println("正确事实是否通过：" + result.accepted());

        assertTrue(result.accepted());
    }

    @Test
    void shouldRejectCrossProductFacts() {

        List<KnowledgeReference> references = mockProductReferences();

        var result = AiGroundingValidator.validate(
                "商品ID 1 的价格为3999元，库存剩余100件",
                "请查询商品ID 1",
                List.of(),
                references
        );

        System.out.println("错误事实是否通过：" + result.accepted());
        System.out.println("校验结果：" + result.failureType());

        // 正确行为：商品1不能使用商品2的价格与库存
        assertFalse(result.accepted(),
                "跨商品事实串用未被拦截");
    }

    private List<KnowledgeReference> mockProductReferences() {

        KnowledgeReference product1 = new KnowledgeReference(
                "product-1",
                "测试商品1",
                "商品ID=1；价格=5999；库存=10",
                "product_mysql",
                1L,
                0.9,
                "mysql-current",
                null
        );

        KnowledgeReference product2 = new KnowledgeReference(
                "product-2",
                "测试商品2",
                "商品ID=2；价格=3999；库存=100",
                "product_mysql",
                2L,
                0.8,
                "mysql-current",
                null
        );

        return List.of(product1, product2);
    }


    // R01-A：未经证实事实的拦截测试
    @Test
    void shouldRejectUnsupportedFacts() {

        List<String> invalidMessages = List.of(
                "商品ID 999目前可以购买",
                "商品ID 888是一款热门手机",
                "该商品价格为8999元",
                "该商品售价为5999元",
                "当前库存为20件",
                "当前库存剩余50件",
                "这款商品目前有货",
                "这款商品已经售罄",
                "你的订单已创建",
                "该商品有优惠券可用"
        );

        int rejected = 0;

        for (String message : invalidMessages) {

            var decision = AiGroundingValidator.validate(
                    message,
                    "请介绍一下商品",
                    List.of()
            );

            if (!decision.accepted()) {
                rejected++;
            }

            assertFalse(
                    decision.accepted(),
                    "未拦截错误事实：" + message
            );

            System.out.println(
                    "REJECTED [" + decision.failureType() +
                            "] " + message
            );
        }

        double rate = rejected * 100.0
                / invalidMessages.size();

        System.out.println(
                "R01-A 拦截率：" + rate + "%"
        );
    }

    // R01-B：安全回答不应被误拒
    @Test
    void shouldAcceptSafeResponses() {

        List<String> safeMessages = List.of(
                "我可以帮你查询商品信息。",
                "请提供具体的商品名称。",
                "目前缺少足够的商品信息。",
                "建议先查询最新的商品详情。",
                "请确认你的购物需求。"
        );

        int accepted = 0;

        for (String message : safeMessages) {

            var decision = AiGroundingValidator.validate(
                    message,
                    "请介绍一下商品",
                    List.of()
            );

            if (decision.accepted()) {
                accepted++;
            }

            assertTrue(
                    decision.accepted(),
                    "正常回答被误拒：" + message
            );
        }

        double rate = accepted * 100.0
                / safeMessages.size();

        System.out.println(
                "R01-B 正常回答接受率：" + rate + "%"
        );
    }
}
