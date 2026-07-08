package com.gofu.cloud.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gofu.shared.context.ProductContext;
import com.gofu.shared.enums.FlowStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProductContext 新字段序列化往返测试（M8-A1）。
 * 云端把 context 整体序列化进 context_json blob（ContextService），
 * 必须验 whiteImages + stage 存进 JSON 再读回不丢——这是碰脊柱改动的核心回归点。
 */
class ProductContextSerdeTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void whiteImagesAndStage_roundTrip() throws Exception {
        ProductContext ctx = new ProductContext();
        ctx.setId("s1");
        ctx.getVisual().getWhiteImages().add("cos://white/主件银色.jpg");
        ctx.getVisual().getMainImages().add("cos://main/01.jpg");
        ctx.setStage(FlowStage.LAYOUT_DONE);

        String json = om.writeValueAsString(ctx);
        ProductContext back = om.readValue(json, ProductContext.class);

        assertEquals(List.of("cos://white/主件银色.jpg"), back.getVisual().getWhiteImages());
        assertEquals(List.of("cos://main/01.jpg"), back.getVisual().getMainImages());
        assertEquals(FlowStage.LAYOUT_DONE, back.getStage());
    }

    @Test
    void stageNull_and_emptyWhiteImages_backwardCompatible() throws Exception {
        // 旧数据：没有 stage、没有 whiteImages 字段
        String oldJson = "{\"id\":\"old1\",\"tenantId\":\"default\",\"status\":\"DRAFT\"}";
        ProductContext back = om.readValue(oldJson, ProductContext.class);

        assertNull(back.getStage());                              // 缺省 null，不炸
        assertNotNull(back.getVisual());
        assertTrue(back.getVisual().getWhiteImages().isEmpty());  // 缺省空列表
    }
}
