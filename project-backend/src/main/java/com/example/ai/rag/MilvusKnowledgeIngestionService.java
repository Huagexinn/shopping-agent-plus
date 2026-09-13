package com.example.ai.rag;

import com.example.dto.product.Response.ProductResponse;
import com.example.service.product.ProductService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 使用 Spring AI 标准组件将商城知识文档切块并写入 Milvus。 */
@Service
public class MilvusKnowledgeIngestionService {
    private static final Logger log = LoggerFactory.getLogger(MilvusKnowledgeIngestionService.class);
    private static final String RESOURCE_PATTERN = "classpath:/rag/*.txt";
    private static final String KNOWLEDGE_VERSION = "v1";
    private static final String KNOWLEDGE_UPDATED_AT = "2026-09-01T00:00:00";

    @Resource
    private VectorStore vectorStore;
    @Resource
    private ProductService productService;

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(500)
            .withMinChunkSizeChars(80)
            .withMinChunkLengthToEmbed(20)
            .withMaxNumChunks(100)
            .withKeepSeparator(true)
            .build();

    @PostConstruct
    public void ingest() {
        try {
            List<Document> documents = loadDocuments();
            if (documents.isEmpty()) {
                throw new IllegalStateException("知识文档不能为空");
            }
            vectorStore.delete(documents.stream().map(Document::getId).toList());
            vectorStore.add(documents);
            log.info("Milvus知识文档写入完成 documentCount={}", documents.size());
        } catch (RuntimeException exception) {
            log.error("Milvus知识文档写入失败 exceptionType={}", exception.getClass().getSimpleName(), exception);
        }
    }

    private List<Document> loadDocuments() {
        List<Document> documents = new ArrayList<>();
        try {
            for (org.springframework.core.io.Resource resource : resolver.getResources(RESOURCE_PATTERN)) {
                String filename = resource.getFilename();
                if (filename == null || filename.isBlank()) {
                    continue;
                }
                String documentId = filename.substring(0, filename.lastIndexOf('.'));
                TextReader reader = new TextReader(resource);
                reader.getCustomMetadata().putAll(Map.of(
                        "documentId", documentId,
                        "title", titleOf(documentId),
                        "sourceType", sourceTypeOf(documentId),
                        "version", KNOWLEDGE_VERSION,
                        "updatedAt", KNOWLEDGE_UPDATED_AT));
                List<Document> chunks = splitter.split(reader.get());
                for (int index = 0; index < chunks.size(); index++) {
                    Document chunk = chunks.get(index);
                    documents.add(chunk.mutate()
                            .id(documentId + "#" + index)
                            .metadata("documentId", documentId)
                            .metadata("title", titleOf(documentId))
                            .metadata("sourceType", sourceTypeOf(documentId))
                            .metadata("version", KNOWLEDGE_VERSION)
                            .metadata("updatedAt", KNOWLEDGE_UPDATED_AT)
                            .build());
                }
            }
            documents.addAll(loadProductDocuments());
        } catch (Exception exception) {
            throw new IllegalStateException("读取知识文档失败", exception);
        }
        return List.copyOf(documents);
    }

    private List<Document> loadProductDocuments() {
        return productService.listPublicForKnowledge().stream()
                .map(this::toProductDocument)
                .toList();
    }

    private Document toProductDocument(ProductResponse product) {
        String documentId = "product-" + product.id();
        String text = String.join("；",
                "品牌=" + valueOf(product.brand()),
                "型号=" + valueOf(product.model()),
                "标题=" + valueOf(product.title()),
                "内存=" + valueOf(product.ramGb()) + "GB",
                "存储=" + valueOf(product.storageGb()) + "GB",
                "描述=" + valueOf(product.description()));
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("documentId", documentId);
        metadata.put("title", product.title());
        metadata.put("sourceType", "product");
        metadata.put("productId", product.id());
        metadata.put("version", "mysql-product");
        metadata.put("updatedAt", product.updatedAt() == null ? "" : product.updatedAt().toString());
        return Document.builder()
                .id(documentId)
                .text(text)
                .metadata(metadata)
                .build();
    }

    private String valueOf(Object value) {
        return value == null ? "未录入" : String.valueOf(value);
    }

    private String titleOf(String documentId) {
        return switch (documentId) {
            case "phone-overview" -> "手机商品介绍";
            case "phone-specifications" -> "手机参数规格说明";
            case "phone-compatibility" -> "手机兼容性说明";
            case "shipping-faq" -> "配送说明";
            case "return-faq" -> "退换货 FAQ";
            case "after-sale-faq" -> "售后 FAQ";
            default -> documentId;
        };
    }

    private String sourceTypeOf(String documentId) {
        return switch (documentId) {
            case "phone-overview" -> "product_overview";
            case "phone-specifications" -> "specification";
            case "phone-compatibility" -> "compatibility";
            case "shipping-faq" -> "shipping_faq";
            case "return-faq" -> "return_faq";
            case "after-sale-faq" -> "after_sale_faq";
            default -> documentId.toLowerCase(Locale.ROOT);
        };
    }
}
