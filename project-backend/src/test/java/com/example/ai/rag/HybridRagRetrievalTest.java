
package com.example.ai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class HybridRagRetrievalTest {

    @Autowired
    private HybridRagService hybridRagService;


    @Test
    void evaluateHitAt5() {

        record TestCase(String query, String expectedId) {}

        List<TestCase> cases = List.of(
                new TestCase("购买手机要关注哪些参数", "phone-overview"),
                new TestCase("iPhone 16 Pro 的配置和价格", "product-1"),
                new TestCase("手机内存和存储有什么区别", "phone-specifications"),
                new TestCase("介绍一下 iPhone 16 Pro 256GB", "product-1"),
                new TestCase("Xiaomi 15 256GB 的商品信息", "product-4"),
                new TestCase("Apple iPhone 16 128GB 的配置", "product-2"),
                new TestCase("手机的运行内存是什么意思", "phone-specifications"),
                new TestCase("购买手机前需要了解什么", "phone-overview"),
                new TestCase("手机兼容性需要注意什么", "phone-compatibility"),
                new TestCase("Samsung Galaxy S25 256GB 的商品信息", "product-3")
        );

        int hits = 0;
        double totalMs = 0;

        for (TestCase test : cases) {

            long start = System.nanoTime();

            var result = hybridRagService.retrieve(test.query());

            double ms = (System.nanoTime() - start) / 1_000_000.0;
            totalMs += ms;

            List<String> top5 = result.references().stream()
                    .sorted(java.util.Comparator.comparingDouble(
                            KnowledgeReference::score).reversed())
                    .limit(5)
                    .map(KnowledgeReference::documentId)
                    .toList();

            boolean hit = top5.contains(test.expectedId());

            if (hit) {
                hits++;
            }

            System.out.printf(
                    "%s | 命中=%s | 预期=%s | 耗时=%.2fms%n",
                    test.query(), hit, test.expectedId(), ms
            );
        }

        System.out.printf(
                "%nHit@5 = %.2f%%%n",
                hits * 100.0 / cases.size()
        );

        System.out.printf(
                "平均混合检索耗时 = %.2fms%n",
                totalMs / cases.size()
        );
    }

}
