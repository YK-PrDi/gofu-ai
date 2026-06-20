package com.gofu.cloud.controller;

import com.gofu.cloud.service.context.ContextService;
import com.gofu.shared.context.ProductContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品全局上下文 CRUD 接口（ADR-003 权威源对外入口）。
 *
 * <p>本地 sync 服务通过这些接口拉取/回写上下文。
 */
@RestController
@RequestMapping("/api/context")
public class ContextController {

    private final ContextService contextService;

    public ContextController(ContextService contextService) {
        this.contextService = contextService;
    }

    /** 创建或更新上下文。 */
    @PostMapping
    public ResponseEntity<ProductContext> save(@RequestBody ProductContext ctx) {
        return ResponseEntity.ok(contextService.save(ctx));
    }

    /** 按 ID 拉取上下文。 */
    @GetMapping("/{id}")
    public ResponseEntity<ProductContext> get(@PathVariable String id) {
        ProductContext ctx = contextService.findById(id);
        return ctx == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(ctx);
    }

    /** 按租户列出（预埋多租户查询入口，ADR-004）。 */
    @GetMapping
    public ResponseEntity<List<ProductContext>> list(
            @RequestParam(defaultValue = "default") String tenantId) {
        return ResponseEntity.ok(contextService.findByTenant(tenantId));
    }
}
