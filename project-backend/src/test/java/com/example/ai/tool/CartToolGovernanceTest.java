package com.example.ai.tool;

import com.example.ai.model.request.CartToolRequest;
import com.example.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CartToolGovernanceTest {

    @Test
    void shouldRejectQuantityExceedingLimit() {

        CartTool cartTool = new CartTool();

        CartToolRequest request = new CartToolRequest(
                "add",
                6L,
                1000,
                "governance-test-001"
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> cartTool.execute(1L, request)
        );

        assertTrue(exception.getMessage().contains("数量必须在1到999之间"));

        System.out.println("G01 PASS: quantity=1000 rejected");
    }
}
