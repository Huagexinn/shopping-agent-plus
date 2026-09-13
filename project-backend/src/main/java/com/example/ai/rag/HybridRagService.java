package com.example.ai.rag;

import com.example.dto.product.Response.ProductResponse;
import com.example.service.product.ProductService;
import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Milvus 只负责语义召回，商品价格、库存和上架状态统一回查 MySQL。 */
@Service
public class HybridRagService {
    private static final int TOP_K = 8;

    @Resource
    private VectorStore vectorStore;
    @Resource
    private ProductService productService;

    public RetrievalResult retrieve(String query) {
        List<Document> documents;
        try {
            documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(TOP_K)
                    .similarityThresholdAll()
                    .build());
        } catch (RuntimeException exception) {
            throw new RagUnavailableException("Milvus 向量检索不可用", exception);
        }
        if (documents == null || documents.isEmpty()) {
            return RetrievalResult.empty();
        }

        List<Long> productIds = documents.stream()
                .map(document -> productIdOf(document.getMetadata().get("productId")))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ProductResponse> products = productService.findPublicByIds(productIds).stream()
                .collect(java.util.stream.Collectors.toMap(ProductResponse::id, product -> product));

        List<KnowledgeReference> references = new ArrayList<>();
        Map<Long, Double> productScores = new LinkedHashMap<>();
        for (Document document : documents) {
            Long productId = productIdOf(document.getMetadata().get("productId"));
            if (productId != null) {
                if (products.containsKey(productId)) {
                    productScores.putIfAbsent(productId, scoreOf(document));
                }
                continue;
            }
            references.add(toKnowledgeReference(document));
        }
        productScores.entrySet().stream()
                .map(entry -> productReference(products.get(entry.getKey()), entry.getValue()))
                .filter(java.util.Objects::nonNull)
                .forEach(references::add);

        return new RetrievalResult(List.copyOf(references), List.copyOf(productScores.keySet()), buildContext(references));
    }

    private KnowledgeReference productReference(ProductResponse product, double score) {
        if (product == null) {
            return null;
        }
        return new KnowledgeReference(
                "product-" + product.id(),
                product.title(),
                productFacts(product),
                "product_mysql",
                product.id(),
                score,
                "mysql-current",
                product.updatedAt());
    }

    private KnowledgeReference toKnowledgeReference(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new KnowledgeReference(
                String.valueOf(metadata.getOrDefault("documentId", document.getId())),
                String.valueOf(metadata.getOrDefault("title", "商城知识")),
                document.getText(),
                String.valueOf(metadata.getOrDefault("sourceType", "knowledge")),
                null,
                scoreOf(document),
                String.valueOf(metadata.getOrDefault("version", "unknown")),
                parseDateTime(metadata.get("updatedAt")));
    }

    private String buildContext(List<KnowledgeReference> references) {
        if (references.isEmpty()) {
            return "未召回相关商城资料。";
        }
        List<KnowledgeReference> sorted = references.stream()
                .sorted(Comparator.comparingDouble(KnowledgeReference::score).reversed())
                .toList();
        StringBuilder context = new StringBuilder("商城检索上下文（商品事实已从 MySQL 回查，优先以此回答）：\n");
        for (KnowledgeReference reference : sorted) {
            context.append("[来源:").append(reference.sourceType()).append("] ")
                    .append(reference.title()).append("\n")
                    .append(reference.content()).append("\n");
        }
        return context.toString();
    }

    private String productFacts(ProductResponse product) {
        return "商品ID=" + product.id()
                + "；品牌=" + valueOf(product.brand())
                + "；型号=" + valueOf(product.model())
                + "；标题=" + valueOf(product.title())
                + "；价格=" + valueOf(product.price())
                + "；库存=" + valueOf(product.stock())
                + "；内存=" + valueOf(product.ramGb()) + "GB"
                + "；存储=" + valueOf(product.storageGb()) + "GB"
                + "；描述=" + valueOf(product.description());
    }

    private String valueOf(Object value) {
        return value == null ? "未录入" : String.valueOf(value);
    }

    private Long productIdOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private double scoreOf(Document document) {
        return document.getScore() == null ? 0.0 : document.getScore();
    }

    private LocalDateTime parseDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        try {
            return value == null ? null : LocalDateTime.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public record RetrievalResult(List<KnowledgeReference> references, List<Long> productIds, String context) {
        static RetrievalResult empty() {
            return new RetrievalResult(List.of(), List.of(), "未召回相关商城资料。");
        }
    }
}
