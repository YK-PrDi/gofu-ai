package com.gofu.cloud.service.lyimage;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 逐级折半降采样单测（08.06，治「×N 角标内产品图模糊扭曲」）。
 *
 * <p>为什么这个测试能证明问题：用**高频条纹**当源图——竖条 1px 黑、间隔 8px，
 * 源图平均亮度精确可算 = (7*255)/8 = 223.125。把它缩到 1/8 宽度：
 * <ul>
 *   <li>单步 bilinear 每个目标像素只采样源图 2×2 邻域，8px 周期里 6 列黑白信息被完全丢弃
 *       → 均值系统性偏离（条纹或消失、或被过度放大成噪点），正是报告里"抽象贴图状"的来源；</li>
 *   <li>逐级折半等效面积平均，全部源像素都参与 → 均值守恒。</li>
 * </ul>
 * 断言均值误差，而不是断言"看起来更清晰"——后者不可自动化。
 */
class DownscaleStepwiseTest {

    /** 竖条纹源图：每 8px 一列黑，其余白。宽高比与目标一致，避免 fit 留白干扰均值。 */
    private static BufferedImage stripes(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                img.setRGB(x, y, (x % 8 == 0) ? 0xFF000000 : 0xFFFFFFFF);
        return img;
    }

    /** 把 src 画到 tw×th 画布（模拟 drawImageFit 的最后一步），返回平均亮度。 */
    private static double meanAfterDraw(BufferedImage src, int tw, int th) {
        BufferedImage canvas = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        long sum = 0;
        for (int y = 0; y < th; y++)
            for (int x = 0; x < tw; x++)
                sum += canvas.getRGB(x, y) & 0xFF;   // 灰度图，取蓝通道即可
        return (double) sum / (tw * th);
    }

    @Test
    void stepwise_preservesMeanBrightness_betterThanSingleStep() {
        int tw = 128, th = 48;              // ×2 主件卡格内实测尺寸量级
        BufferedImage src = stripes(tw * 8, th * 8);   // 1024×384，缩放比 8:1
        double truth = (7 * 255.0) / 8;    // = 223.125

        double single = meanAfterDraw(src, tw, th);                                  // 改前
        double stepwise = meanAfterDraw(ShowerCompositor.downscaleStepwise(src, tw, th), tw, th);  // 改后

        double errSingle = Math.abs(single - truth), errStepwise = Math.abs(stepwise - truth);
        System.out.printf("[降采样对照] 真值=%.3f 单步=%.3f(误差%.3f) 逐级折半=%.3f(误差%.3f)%n",
                truth, single, errSingle, stepwise, errStepwise);
        assertTrue(errStepwise < errSingle,
                "逐级折半应更接近真实均值：stepwise 误差=" + errStepwise + " 单步误差=" + errSingle);
        assertTrue(errStepwise < 3.0, "逐级折半均值误差应可忽略，实际=" + errStepwise);
    }

    @Test
    void noUpscale_and_smallRatio_returnSourceUntouched() {
        BufferedImage src = stripes(100, 40);
        assertSame(src, ShowerCompositor.downscaleStepwise(src, 200, 80), "放大不该走折半");
        assertSame(src, ShowerCompositor.downscaleStepwise(src, 60, 24), "不到 2 倍不该走折半");
    }

    /**
     * 透明通道必须活着。`compositeMainQtyCardAt` 喂进来的是 `whiteToTransparent` 抠过的图，
     * 若折半的中间层误用 `TYPE_INT_RGB`，alpha=0 的像素会被当成黑色渲染 →
     * 卡内产品变成一个个黑方块。这条断言直接守住那个回归，比在成品图上数黑像素可靠
     * （白底产品本身含深色件，数黑像素会误报）。
     */
    @Test
    void alphaSurvivesStepwiseDownscale() {
        int w = 1024, h = 384;
        BufferedImage src = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        // 左半全透明、右半不透明红色
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                src.setRGB(x, y, x < w / 2 ? 0x00000000 : 0xFFFF0000);

        BufferedImage out = ShowerCompositor.downscaleStepwise(src, 128, 48);
        int leftAlpha  = (out.getRGB(out.getWidth() / 8, out.getHeight() / 2) >>> 24);
        int rightAlpha = (out.getRGB(out.getWidth() * 7 / 8, out.getHeight() / 2) >>> 24);

        assertEquals(0, leftAlpha, "透明区缩完必须仍透明(alpha=0)，实际 alpha=" + leftAlpha
                + " —— 若为 255 说明中间层用了 RGB，卡内产品会变黑块");
        assertEquals(255, rightAlpha, "不透明区缩完必须仍不透明，实际 alpha=" + rightAlpha);
    }

    @Test
    void targetSizeIsNeverUndershot() {
        BufferedImage out = ShowerCompositor.downscaleStepwise(stripes(1024, 384), 128, 48);
        assertTrue(out.getWidth() >= 128 && out.getHeight() >= 48,
                "折半不得缩到小于目标尺寸(否则最后一步又变放大)，实际=" + out.getWidth() + "x" + out.getHeight());
    }
}
