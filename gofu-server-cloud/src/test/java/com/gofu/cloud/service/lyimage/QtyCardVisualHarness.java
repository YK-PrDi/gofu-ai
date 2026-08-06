package com.gofu.cloud.service.lyimage;

import com.gofu.cloud.config.LyImageProperties;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ×N 主件卡的**肉眼验证台**（08.06，配合 [[downscaleStepwise]] 那次修复）。
 *
 * <p>不是断言型单测——"缩略图够不够清晰"没法自动判定，只能出图看。本类拿**真样本**
 * （真实 AI 出的架类主图 + 真实白底锅盖图）真调 `compositeMainQtyCardAt`，把成品写进
 * `shots/sku-gen/qtycard-<N>/`，零 API 成本、不用起 Spring、不用重启 5020。
 *
 * <p><b>类名故意不叫 `*Test`</b>：surefire 默认只收 `*Test`/`Test*`/`*Tests`/`*TestCase`，
 * 所以本类**不会**进常规 `mvn test`（它要往磁盘写图，不该混进回归）。要跑就显式点名：
 * <pre>mvn -o -pl gofu-server-cloud test -Dtest=QtyCardVisualHarness -Dsurefire.failIfNoSpecifiedTests=false</pre>
 * 多个类用**逗号**分隔；用 `+` 会静默一个都不跑却报 BUILD SUCCESS（08.06 踩过）。
 *
 * <p>看图时对着两条：① 卡内 N 个缩略图边缘清晰、能认出是锅盖，不是模糊色块；
 * ② 缩略图背景是白卡颜色，**不是黑方块**（黑块 = 折半中间层误用了 RGB，
 * 那条已由 `DownscaleStepwiseTest#alphaSurvivesStepwiseDownscale` 守住）。
 */
class QtyCardVisualHarness {

    /** 样本路径按仓库根解析；surefire 的 cwd 可能是模块目录，故两处都试。 */
    private static File resolve(String rel) {
        File f = new File(rel);
        return f.isFile() ? f : new File("../" + rel);
    }

    private static ShowerCompositor compositor(String outRoot) {
        LyImageProperties props = new LyImageProperties();
        props.getPaths().setUserDataDir(outRoot);
        return new ShowerCompositor(props);
    }

    @Test
    void renderQtyCards_forEyeball() throws Exception {
        File base = resolve("shots/lab-main-锅盖架-new1.jpg");
        // 两种样本形态都要跑：侧立锅盖是**横长**、落地架体是**竖长**。
        // 08.06 的 bbox 修复只对竖长产品改变版式选择，只拿横长样本测**看不出差别**
        // （同 [[ab-sample-must-contradict]]：样本必须能让被测变量显形）。
        File lid  = resolve("gofu-server-cloud/src/main/resources/assets/collectibles/锅盖-侧立白底.png");
        File rack = resolve("gofu-server-cloud/src/main/resources/assets/base/shelf-落地锅盖架-米奇款.png");
        assumeTrue(base.isFile() && lid.isFile() && rack.isFile(),
                "缺样本，跳过肉眼验证台：base=" + base.getPath());

        for (Object[] s : new Object[][]{{"lid", lid}, {"rack", rack}}) {
            renderOne(base, (File) s[1], (String) s[0]);
        }
    }

    private void renderOne(File base, File white, String tag) throws Exception {
        BufferedImage b = ImageIO.read(base), w = ImageIO.read(white);
        System.out.printf("[验证台·%s] 底图 %dx%d，白底产品图 %dx%d（整图长宽判定=%s）%n",
                tag, b.getWidth(), b.getHeight(), w.getWidth(), w.getHeight(),
                w.getWidth() >= w.getHeight() ? "横长" : "竖长");

        File outRoot = resolve("shots").isDirectory() ? resolve("shots") : new File("shots");
        // 网格选择由生产代码自己打日志（`×N 主件卡: 产品bbox … → 网格 …`）。
        // 本类**不再自算**：08.06 抄过一份，生产改用 cropToContent 之后抄的那份仍打印旧数字，
        // 差点据此判定"改动没生效"——同 PromptLab 那条教训，凡抄一份必然漂移。
        for (int n : new int[]{2, 3, 6}) {
            File out = compositor(outRoot.getPath())
                    .compositeMainQtyCardAt(base, white, n, "qtycard-" + tag + "-" + n, 1, "肉眼验证");
            assertTrue(out.isFile() && out.length() > 0, "N=" + n + " 没出图");
            System.out.printf("[验证台·%s] N=%d → %s (%d KB)%n", tag, n, out.getPath(), out.length() / 1024);
        }
    }
}
