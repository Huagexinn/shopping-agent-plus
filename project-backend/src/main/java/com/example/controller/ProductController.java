package com.example.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.ApiResponse;
import com.example.dto.product.Request.ProductQueryRequest;
import com.example.dto.product.Response.ProductResponse;
import com.example.exception.BusinessException;
import com.example.exception.ResourceNotFoundException;
import com.example.service.product.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/** 提供匿名访问的手机商品分页列表。 */
@RestController
@RequestMapping("/api/products")
@Tag(name = "商品", description = "手机商品公开浏览和详情接口")
@Validated
public class ProductController {
    @Resource
    private ProductService productService;

    /** 分页返回当前上架商品，默认按创建时间倒序排列。 */
    @GetMapping
    @Operation(
            summary = "分页查询上架商品",
            description = "匿名访问商品列表，支持分页和价格、品牌、排序等查询参数；默认仅返回未逻辑删除的上架商品。")
    public ApiResponse<Page<ProductResponse>> list(
            @ParameterObject @Valid @ModelAttribute ProductQueryRequest request) {
        return ApiResponse.success(productService.page(request));
    }

    /** 返回指定的上架商品详情；下架、删除或不存在的商品不对外暴露。 */
    @GetMapping("/{id}")
    @Operation(
            summary = "查询商品详情",
            description = "匿名查询指定上架商品的当前价格、库存和展示信息；商品不存在、下架或已逻辑删除时返回 404。")
    public ApiResponse<ProductResponse> detail(
            @Parameter(description = "商品业务 ID，必须为正整数", example = "1")
            @PathVariable @Positive(message = "商品ID必须是正整数") Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException("商品ID必须是正整数");
        }
        ProductResponse product = productService.findPublicById(id);
        if (product == null) {
            throw new ResourceNotFoundException("商品不存在");
        }
        return ApiResponse.success(product);
    }
}
