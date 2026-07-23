package com.gofu.local.controller;

import com.gofu.local.service.LifecycleService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作台前端生命周期心跳端点。配合 LifecycleService 看门狗，实现"关掉工作台→自动退出所有服务"。
 * 端点极轻(无返回体)，前端每几秒打一次 heartbeat；页面关闭时 sendBeacon 打 closing。
 */
@RestController
public class LifecycleController {

    private final LifecycleService lifecycle;

    public LifecycleController(LifecycleService lifecycle) { this.lifecycle = lifecycle; }

    /** 前端定时报活。 */
    @PostMapping("/api/lifecycle/heartbeat")
    public void heartbeat() { lifecycle.heartbeat(); }

    /** 页面关闭信号(sendBeacon)。 */
    @PostMapping("/api/lifecycle/closing")
    public void closing() { lifecycle.closing(); }
}
