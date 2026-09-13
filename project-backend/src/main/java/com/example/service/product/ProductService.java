package com.example.service.product;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dto.product.Request.ProductQueryRequest;
import com.example.dto.product.Response.ProductResponse;
import com.example.entity.ProductEntity;

import java.util.List;

/** 手机商品查询和库存状态服务。 */
public interface ProductService {
    /** 分页查询公开上架商品。 */
    Page<ProductResponse> page(ProductQueryRequest request);

    /** 按关键词和结构化条件分页搜索公开上架商品。 */
    Page<ProductResponse> search(ProductQueryRequest request);

    /** 查询可公开展示的商品详情；不存在或下架时返回 null。 */
    ProductResponse findPublicById(Long id);

    /** 按 ID 批量查询当前可公开展示的商品，用于向量召回后的事实回查。 */
    List<ProductResponse> findPublicByIds(List<Long> ids);

    /** 查询全部当前在售商品，用于构建商品向量索引。 */
    List<ProductResponse> listPublicForKnowledge();

    /** 查询商品当前数据，供购物车和订单服务重新读取。 */
    ProductEntity findById(Long id);

    /**
     * 失效商品详情缓存，供库存变更等写操作在事务提交后调用。
     * 缓存失效失败只记录告警，不影响已提交的业务数据。
     */
    void evictDetailCache(Long productId);
}
