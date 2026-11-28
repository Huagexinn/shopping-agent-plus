package com.example.service.ai.impl;

import com.example.ai.memory.*;
import com.example.ai.rag.HybridRagService;
import com.example.ai.tool.*;
import com.example.mapper.*;
import com.example.service.cart.CartService;
import com.example.service.cart.impl.CartServiceImpl;
import com.example.service.product.impl.ProductServiceImpl;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.*;

/** 复用真实订单数据库夹具，连接真实 CartTool/业务事务/审计；仅模型、记忆和检索召回为可控替身。 */
final class AgentEvaluationFixture {
    final AiOrchestrationCore core = new AiOrchestrationCore();
    final JdbcAgentRunService runs = new JdbcAgentRunService(OrderConfirmationMysqlIT.jdbc,OrderConfirmationMysqlIT.manager);
    final CartTool cart = new CartTool();
    final FrozenToolExecutor executor = new FrozenToolExecutor();
    final AgentRecoveryService recovery;
    AgentEvaluationFixture() {
        var products = new ProductServiceImpl();
        ReflectionTestUtils.setField(products,"productMapper",OrderConfirmationMysqlIT.mapper(ProductMapper.class));
        var service = new CartServiceImpl();
        ReflectionTestUtils.setField(service,"cartMapper",OrderConfirmationMysqlIT.mapper(CartMapper.class));
        ReflectionTestUtils.setField(service,"cartItemMapper",OrderConfirmationMysqlIT.mapper(CartItemMapper.class));
        ReflectionTestUtils.setField(service,"productService",products);
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(OrderConfirmationMysqlIT.manager,new AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(cart,"cartService",(CartService)proxy.getProxy());
        var idempotency = new CartOperationIdempotencyServiceImpl();
        ReflectionTestUtils.setField(idempotency,"recordMapper",OrderConfirmationMysqlIT.mapper(CartOperationRecordMapper.class));
        ReflectionTestUtils.setField(idempotency,"transactionManager",OrderConfirmationMysqlIT.manager);
        ReflectionTestUtils.setField(cart,"operationIdempotencyService",idempotency);
        var detail = new ProductDetailTool(); ReflectionTestUtils.setField(detail,"productService",products);
        var inventory = new InventoryTool(); ReflectionTestUtils.setField(inventory,"productService",products);
        var query = new OrderQueryTool(); ReflectionTestUtils.setField(query,"orderService",OrderConfirmationMysqlIT.orders);
        for (Object[] field : List.of(new Object[]{"agentRunService",runs}, new Object[]{"cartTool",cart},new Object[]{"productDetailTool",detail},new Object[]{"inventoryTool",inventory},new Object[]{"orderQueryTool",query},new Object[]{"orderDraftService",OrderConfirmationMysqlIT.drafts})) ReflectionTestUtils.setField(core,(String)field[0],field[1]);
        ReflectionTestUtils.setField(core,"conversationMemoryService",new ConversationMemoryService() {
            public List<ConversationMessage> getHistory(Long u,String c) { return List.of(); }
            public void append(Long u,String c,List<ConversationMessage> messages) { }
        });
        // 检索是明确的测试替身，不把本评测写成 Milvus/RAG 召回质量验证。
        ReflectionTestUtils.setField(core,"hybridRagService",new HybridRagService() {
            @Override public RetrievalResult retrieve(String queryText) { return new RetrievalResult(List.of(),List.of(),"固定评测目录中没有检索候选。请对用户明确给出的商品ID使用详情工具。"); }
        });
        ReflectionTestUtils.setField(executor,"cartTool",cart);
        ReflectionTestUtils.setField(executor,"productDetailTool",detail);
        ReflectionTestUtils.setField(executor,"inventoryTool",inventory);
        ReflectionTestUtils.setField(executor,"orderQueryTool",query);
        recovery = new AgentRecoveryService(OrderConfirmationMysqlIT.jdbc,OrderConfirmationMysqlIT.manager,runs,executor);
    }
    static void reset(int quantity) {
        var db=OrderConfirmationMysqlIT.jdbc;
        for(String table:List.of("agent_step_recovery_attempts","agent_run_steps","agent_runs","cart_operation_records")) db.update("DELETE FROM "+table);
        new OrderConfirmationMysqlIT().reset();
        db.update("DELETE FROM cart_items");
        if(quantity>0) OrderConfirmationMysqlIT.addCart(1,quantity);
        db.update("UPDATE products SET ram_gb=8,storage_gb=256");
        db.update("INSERT INTO carts(id,user_id,created_at,updated_at,deleted) VALUES(2,2,NOW(),NOW(),0)");
        db.update("INSERT INTO cart_items(cart_id,product_id,quantity,display_price,created_at,updated_at,deleted) VALUES(2,2,3,50,NOW(),NOW(),0)");
    }
}
