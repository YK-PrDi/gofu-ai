package com.gofu.cloud.model;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class GenerationTask {

    private final String id;
    /** pending / running / done / stopped / error */
    private volatile String status = "pending";
    // M14：并发生图下多线程递增，用 AtomicInteger 保证不丢增量（原 volatile int++ 非原子）。
    private final AtomicInteger progress = new AtomicInteger(0);
    // 07.31：progress 只反映"已尝试(成功+失败)张数"，跟"真的出了几张图"脱钩——欠费/超时导致全部失败时，
    // progress 仍会跑到接近 total，前端进度条显示98%但实际0张出图。加这个字段单独统计成功数，供前端展示"真实进度"。
    private final AtomicInteger successCount = new AtomicInteger(0);
    private volatile int total;
    private volatile boolean cancelled = false;
    private volatile String currentProduct = "";
    private final List<Map<String, Object>> results = new CopyOnWriteArrayList<>();
    private final long createdAt = System.currentTimeMillis();

    public GenerationTask(String id, int total) {
        this.id = id;
        this.total = total;
    }

    public String getId() { return id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgress() { return progress.get(); }
    public void incrementProgress() { this.progress.incrementAndGet(); }
    public int getSuccessCount() { return successCount.get(); }
    public void incrementSuccess() { this.successCount.incrementAndGet(); }
    public int getTotal() { return total; }
    /** 8c 交叉并行:SKU 数要等布局完成才知道,进度总数运行中追加(单线程 layout 回调调,volatile 够用)。 */
    public void addTotal(int delta) { this.total += delta; }
    public boolean isCancelled() { return cancelled; }
    public void cancel() { this.cancelled = true; }
    public String getCurrentProduct() { return currentProduct; }
    public void setCurrentProduct(String p) { this.currentProduct = p; }
    public List<Map<String, Object>> getResults() { return results; }
    public void addResult(Map<String, Object> r) { results.add(r); }
    public long getCreatedAt() { return createdAt; }
}
