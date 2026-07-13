package com.gofu.local.service.listing;

import com.gofu.local.config.AppProperties;
import com.gofu.local.model.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多店管理单测（P4）。用临时目录当 userDataDir，验证 stores.json 存取、
 * 店铺名→profile 解析(精确/子串)、每店独立路径派生。
 */
class StoreServiceTest {

    private StoreService svc(Path tmp) {
        AppProperties props = new AppProperties();
        props.getPaths().setUserDataDir(tmp.toString());
        return new StoreService(props);
    }

    @Test
    void 空文件_返回空表不抛(@TempDir Path tmp) {
        assertTrue(svc(tmp).loadStores().isEmpty());
    }

    @Test
    void 存取往返(@TempDir Path tmp) throws Exception {
        StoreService s = svc(tmp);
        s.saveStores(List.of(new Store("GOFU厨卫配件官方旗舰店", "store_13"),
                             new Store("GOFU家居建材官方旗舰店", "store_01")));
        List<Store> loaded = s.loadStores();
        assertEquals(2, loaded.size());
        assertEquals("store_13", loaded.get(0).getProfile());
        assertTrue(s.storesFile().isFile());
    }

    @Test
    void 店铺名解析_精确与子串(@TempDir Path tmp) throws Exception {
        StoreService s = svc(tmp);
        s.saveStores(List.of(new Store("GOFU厨卫配件官方旗舰店", "store_13")));
        // 精确
        assertEquals("store_13", s.resolveProfileByName("GOFU厨卫配件官方旗舰店"));
        // 子串（文件夹名多了后缀）
        assertEquals("store_13", s.resolveProfileByName("GOFU厨卫配件官方旗舰店-7月"));
        // 无匹配
        assertNull(s.resolveProfileByName("不存在的店"));
        assertNull(s.resolveProfileByName(""));
    }

    @Test
    void 每店独立路径派生(@TempDir Path tmp) {
        StoreService s = svc(tmp);
        String udd = s.userDataDirOf("store_13");
        String cp = s.cookiesPathOf("store_13");
        assertTrue(udd.replace('\\', '/').endsWith("stores/store_13/pdd_browser_profile"));
        assertTrue(cp.replace('\\', '/').endsWith("stores/store_13/pdd_cookies.json"));
        // 不同店互相隔离
        assertNotEquals(s.userDataDirOf("store_01"), udd);
    }
}
