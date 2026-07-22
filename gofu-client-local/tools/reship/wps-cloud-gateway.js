import { chromium as defaultChromium } from "playwright-core";

function trim(value) {
  return String(value ?? "").trim();
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function isBrowserClosedError(error) {
  return /Target page, context or browser has been closed/i.test(String(error?.message ?? error));
}

export function normalizeCloudOrders(values, startRow = 2) {
  if (!Array.isArray(values)) return [];
  return values.flatMap((row, index) => {
    const orderNo = trim(row?.[0]);
    if (!orderNo) return [];
    return [{ row: startRow + index, orderNo, merchantCode: trim(row?.[2]) }];
  });
}

export function findFirstSafeCloudRow(values, startRow = 2) {
  for (let index = 0; index < values.length; index += 1) {
    const row = values[index] ?? [];
    if ([row[0], row[1], row[2], row[6]].every((value) => trim(value) === "")) {
      return startRow + index;
    }
  }
  throw new Error("目标 WPS 云表没有 B/C/D/H 全空的安全空行");
}

export function buildRemarkPayload(record, row) {
  return {
    row,
    B: record.B,
    C: record.C,
    D: record.D,
    remarkText: String(record.remarkText ?? ""),
    image: record.imageBytes ? {
      extension: record.extension,
      base64: Buffer.from(record.imageBytes).toString("base64"),
    } : null,
  };
}

async function streamToBuffer(stream) {
  const chunks = [];
  for await (const chunk of stream) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks);
}

export class WpsCloudGateway {
  constructor({
    chromium = defaultChromium,
    userDataDir,
    timeout = 30_000,
  }) {
    this.chromium = chromium;
    this.userDataDir = userDataDir;
    this.timeout = timeout;
    this.context = null;
  }

  async ensureContext() {
    if (!this.context) {
      const context = await this.chromium.launchPersistentContext(this.userDataDir, {
        channel: "msedge",
        headless: false,
        viewport: null,
        acceptDownloads: true,
        args: ["--start-maximized"],
      });
      this.context = context;
      context.once?.("close", () => {
        if (this.context === context) this.context = null;
      });
    }
    return this.context;
  }

  async getPage(url) {
    for (let attempt = 1; attempt <= 2; attempt += 1) {
      try {
        const context = await this.ensureContext();
        let page = context.pages().find((item) => item.url().includes(new URL(url).pathname));
        if (!page) page = context.pages().find((item) => item.url() === "about:blank") ?? await context.newPage();
        if (!page.url().includes(new URL(url).pathname)) {
          await page.goto(url, { waitUntil: "domcontentloaded" });
        }
        await page.bringToFront();
        return page;
      } catch (error) {
        if (attempt === 2 || !isBrowserClosedError(error)) throw error;
        this.context = null;
        await delay(300);
      }
    }
    throw new Error("无法重新打开 WPS 浏览器会话");
  }

  async waitDocument(page) {
    try {
      await page.waitForFunction(() => window.WPSOpenApi?.Application, { timeout: this.timeout });
      await page.evaluate(() => window.WPSOpenApi.documentReadyPromise);
      return true;
    } catch {
      return false;
    }
  }

  async openLogin(url = "https://www.kdocs.cn/") {
    await this.getPage(url);
    return { ok: true, code: "LOGIN_WINDOW_OPENED", message: "WPS 登录窗口已打开" };
  }

  async inspectDocument(url) {
    const page = await this.getPage(url);
    if (!await this.waitDocument(page)) {
      return { ok: false, code: "LOGIN_REQUIRED", message: "需要先登录 WPS" };
    }
    return page.evaluate(async () => {
      const app = window.WPSOpenApi.Application;
      const workbook = await app.ActiveWorkbook;
      const permission = await app.DocumentPermission;
      const readOnly = await workbook.ReadOnly;
      const editable = !readOnly && Boolean(await permission.edit);
      return editable
        ? { ok: true, code: "EDITABLE", message: "WPS 文档具备编辑权限" }
        : { ok: false, code: "READ_ONLY", message: "当前 WPS 文档只有查看权限" };
    });
  }

  async readOrders(url) {
    const page = await this.getPage(url);
    if (!await this.waitDocument(page)) throw new Error("WPS 尚未登录");
    const data = await page.evaluate(async () => {
      const sheet = await window.WPSOpenApi.Application.ActiveSheet;
      const usedRange = await sheet.UsedRange;
      const rowCount = Math.max(1, Number(await (await usedRange.Rows).Count));
      if (rowCount < 2) return { values: [], startRow: 2 };
      const range = await sheet.Range(`B2:D${rowCount}`);
      const values = await range.Value2;
      return { values: Array.isArray(values) ? values : [[values]], startRow: 2 };
    });
    return normalizeCloudOrders(data.values, data.startRow);
  }

  async markOrderRowRed(url, { row, orderNo }) {
    const page = await this.getPage(url);
    if (!await this.waitDocument(page)) throw new Error("WPS 尚未登录");
    return page.evaluate(async ({ row, orderNo }) => {
      const app = window.WPSOpenApi.Application;
      const workbook = await app.ActiveWorkbook;
      const sheet = await app.ActiveSheet;
      const orderCell = await sheet.Range(`B${row}`);
      if (String(await orderCell.Value2 ?? "").trim() !== orderNo) {
        throw new Error(`第 ${row} 行订单号已经变化，拒绝标红`);
      }
      const range = await sheet.Range(`A${row}:M${row}`);
      const interior = await range.Interior;
      interior.Color = await app.RGB(255, 0, 0);
      await workbook.Save();
      return { ok: true, row };
    }, { row, orderNo });
  }

  async downloadLatest(url) {
    const page = await this.getPage(url);
    if (!await this.waitDocument(page)) throw new Error("WPS 尚未登录");
    if (!await page.getByText("下载", { exact: true }).isVisible().catch(() => false)) {
      await page.locator(".app-header-more-btn").click();
    }
    const downloadPromise = page.waitForEvent("download", { timeout: this.timeout });
    await page.getByText("下载", { exact: true }).click();
    const download = await downloadPromise;
    return streamToBuffer(await download.createReadStream());
  }

  async appendRemark(url, record) {
    const page = await this.getPage(url);
    if (!await this.waitDocument(page)) throw new Error("WPS 尚未登录");
    const matrix = await page.evaluate(async () => {
      const sheet = await window.WPSOpenApi.Application.ActiveSheet;
      const usedRange = await sheet.UsedRange;
      const usedRows = Math.max(1, Number(await (await usedRange.Rows).Count));
      const maxRow = Math.max(usedRows + 100, 101);
      const values = await (await sheet.Range(`B2:H${maxRow}`)).Value2;
      return Array.isArray(values) ? values : [[values]];
    });
    if (matrix.some((row) => trim(row?.[0]) === trim(record.B))) {
      return { ok: true, code: "REMARK_EXISTS", message: "备注订单已存在，跳过追加" };
    }
    const targetRow = findFirstSafeCloudRow(matrix, 2);
    const payload = buildRemarkPayload(record, targetRow);
    return page.evaluate(async (data) => {
      const app = window.WPSOpenApi.Application;
      const workbook = await app.ActiveWorkbook;
      const sheet = await app.ActiveSheet;
      const hCell = await sheet.Range(`H${data.row}`);
      hCell.Value2 = data.remarkText;
      if (data.image) {
        const bytes = Uint8Array.from(atob(data.image.base64), (character) => character.charCodeAt(0));
        const mime = data.image.extension === "png" ? "image/png" : "image/jpeg";
        const file = new File([bytes], `remark.${data.image.extension}`, { type: mime });
        await hCell.InsertImage(file);
        const deadline = Date.now() + 20_000;
        while (Date.now() < deadline) {
          if (/DISPIMG/.test(String(await hCell.Value2))) break;
          await new Promise((resolve) => setTimeout(resolve, 250));
        }
        if (!/DISPIMG/.test(String(await hCell.Value2))) throw new Error("WPS 图片写入超时");
        const entireRow = await hCell.EntireRow;
        entireRow.RowHeight = 102;
      }
      const bcd = await sheet.Range(`B${data.row}:D${data.row}`);
      bcd.Value2 = [[data.B, data.C, data.D]];
      await workbook.Save();
      const verify = await bcd.Value2;
      if (JSON.stringify(verify) !== JSON.stringify([[data.B, data.C, data.D]])) {
        throw new Error("WPS B/C/D 写入后校验失败");
      }
      const hValue = String(await hCell.Value2 ?? "").trim();
      if (data.image && !/DISPIMG/.test(hValue)) {
        throw new Error("WPS H 列图片写入后校验失败");
      }
      if (!data.image && hValue !== data.remarkText.trim()) {
        throw new Error("WPS H 列备注文字写入后校验失败");
      }
      return {
        ok: true,
        code: "REMARK_APPENDED",
        message: `备注订单已追加到第 ${data.row} 行`,
        targetRow: data.row,
      };
    }, payload);
  }

  async appendImageNote(url, record) {
    return this.appendRemark(url, record);
  }
}
