package com.example.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger/OpenAPI 文档的基础信息配置。 */
@Configuration
public class OpenApiConfig {
    /** 创建商城后端的 OpenAPI 元数据。 */
    @Bean
    public OpenAPI shoppingAgentOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("手机商城与 AI 购物助手 API")
                .description("提供手机商品浏览、认证及后续购物助手能力的 HTTP JSON 接口。商品公开接口无需登录。")
                .version("v1"));
    }
}
