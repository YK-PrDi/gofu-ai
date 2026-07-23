import { chromium as defaultChromium } from "playwright-core";

const ERP_URL = "https://erp.superboss.cc/index.html#/index/";

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

export function findStrictMatchIndex(values, expected) {
  for (let index = values.length - 1; index >= 0; index -= 1) {
    if (String(values[index]).trim() === String(expected).trim()) return index;
  }
  return -1;
}

function normalizeOrderNo(value) {
  return String(value ?? "").replace(/[\u200B-\u200D\u2060\uFEFF]/g, "").trim();
}

export function extractPlatformOrderNo(text) {
  const value = String(text ?? "").match(/平台单号\s*[：:]\s*([^\s]+)/)?.[1] ?? "";
  return normalizeOrderNo(value.replace(/复制$/u, ""));
}

async function readPlatformOrderCandidates(row, textTimeout) {
  const structured = typeof row.evaluate === "function" ? await row.evaluate((element) => {
    const normalize = (value) => String(value ?? "")
      .replace(/[\u200B-\u200D\u2060\uFEFF]/g, "")
      .trim();
    const labels = [...element.querySelectorAll("*")]
      .filter((node) => /^平台单号\s*[：:]$/u.test(normalize(node.textContent)));
    const values = [];
    for (const label of labels) {
      const container = label.parentElement;
      if (!container) continue;
      const leaves = [...container.querySelectorAll("*")]
        .filter((node) => node !== label && node.children.length === 0)
        .map((node) => normalize(node.textContent))
        .filter((value) => value && value !== "复制");
      values.push(...leaves);
      values.push(
        normalize(container.innerText ?? container.textContent)
          .replace(/^平台单号\s*[：:]\s*/u, "")
          .replace(/\s*复制$/u, ""),
      );
    }
    return [...new Set(values.filter(Boolean))];
  }).catch(() => []) : [];
  const fallback = extractPlatformOrderNo(await row.innerText({ timeout: textTimeout }));
  return [...structured, fallback].map(normalizeOrderNo).filter(Boolean);
}

function isResultRefreshError(error) {
  return /Timeout .*exceeded|waiting for locator|not attached|Target page.*closed/i
    .test(String(error?.message ?? error));
}

// 可补发的订单状态(ERP 只允许这几种状态补发;其余点补发会弹"请选择…的订单进行补发")。
const RESHIPPABLE_STATUSES = ["交易成功", "卖家已发货", "交易关闭"];

/**
 * 挑可补发行(提速版):按一个平台单号搜出的多行【全是同一订单的拆分显示】(用户确认),故无需逐行展开核对单号,
 * 也不靠位置(不一定最后一行),唯一判据=【订单状态】。遍历所有行读状态单元格(.trade-tradeStatus,可见即读,
 * 不展开),挑状态属于可补发(交易成功/卖家已发货/交易关闭)的行,取最后一个。
 * 都无可补发状态→返回 null(processOrder 判 ORDER_NOT_FOUND 标红跳过);另有 ERP 弹窗兜底双保险。
 */
export async function pickReshipableRow(rows, { textTimeout = 5_000 } = {}) {
  const count = await rows.count();
  if (count === 0) return null;
  let picked = null;
  for (let index = 0; index < count; index += 1) {
    const row = rows.nth(index);
    try {
      const statusCell = row.locator(".trade-tradeStatus").first();
      if (!(await statusCell.count())) continue;
      const statusText = (await statusCell.innerText({ timeout: textTimeout })).replace(/\s+/g, "");
      if (RESHIPPABLE_STATUSES.some((s) => statusText.includes(s))) picked = row;   // 取最后一个可补发行
    } catch (_) { /* 读该行状态失败,跳过 */ }
  }
  return picked;
}

async function firstVisible(locator) {
  const count = await locator.count();
  for (let index = 0; index < count; index += 1) {
    const candidate = locator.nth(index);
    if (await candidate.isVisible().catch(() => false)) return candidate;
  }
  return null;
}

async function findExistingInFrames(page, factory) {
  for (const frame of page.frames()) {
    const candidates = factory(frame);
    if (await candidates.count().catch(() => 0)) {
      return { frame, locator: candidates.first() };
    }
  }
  return null;
}

async function findInFrames(page, factory) {
  for (const frame of page.frames()) {
    const candidate = await firstVisible(factory(frame)).catch(() => null);
    if (candidate) return { frame, locator: candidate };
  }
  return null;
}

async function findText(page, value, exact = true) {
  return findInFrames(page, (frame) => frame.getByText(value, { exact }));
}

async function findInputContext(page, patterns) {
  for (const pattern of patterns) {
    const byPlaceholder = await findInFrames(page, (frame) => frame.getByPlaceholder(pattern));
    if (byPlaceholder) return byPlaceholder;
    const byLabel = await findInFrames(page, (frame) => frame.getByLabel(pattern));
    if (byLabel) return byLabel;
  }
  return null;
}

async function findVisibleInFrame(frame, factories) {
  for (const factory of factories) {
    const locator = await firstVisible(factory(frame)).catch(() => null);
    if (locator) return locator;
  }
  return null;
}

async function findErpLoginForm(page) {
  for (const frame of page.frames()) {
    const company = await findVisibleInFrame(frame, [
      (current) => current.locator("#login-company"),
      (current) => current.getByPlaceholder(/公司|企业/),
      (current) => current.getByLabel(/公司|企业/),
    ]);
    const account = await findVisibleInFrame(frame, [
      (current) => current.locator("#login-account"),
      (current) => current.getByPlaceholder(/账号|用户名|用户/),
      (current) => current.getByLabel(/账号|用户名|用户/),
    ]);
    const password = await findVisibleInFrame(frame, [
      (current) => current.locator("#login-password"),
      (current) => current.getByPlaceholder(/密码/),
      (current) => current.getByLabel(/密码/),
    ]);
    const login = await findVisibleInFrame(frame, [
      (current) => current.locator("#login-btn"),
      (current) => current.getByText(/登录/),
    ]);
    if (!company && !account && !password && !login) continue;
    if (!company || !account || !password || !login) {
      throw new Error("ERP 登录页面已出现，但公司、账号、密码或登录按钮不完整");
    }
    return { frame, company, account, password, login };
  }
  return null;
}

async function findAuthenticatedMarker(page) {
  return findExistingInFrames(
    page,
    (frame) => frame.getByText("订单管理", { exact: true }),
  );
}

async function waitForErpAuthState(page, {
  timeout = 30_000,
  pause = () => delay(400),
} = {}) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await findAuthenticatedMarker(page)) return { type: "authenticated" };
    const form = await findErpLoginForm(page);
    if (form) return { type: "login", form };
    await pause();
  }
  throw new Error("无法判断 ERP 登录状态：未检测到登录表单或订单管理");
}

const STARTUP_DIALOG_SELECTOR = ".el-dialog, .el-message-box, .layui-layer-dialog, .layui-layer-page";
const STARTUP_DIALOG_CLOSE_SELECTOR = [
  ".el-dialog__headerbtn",
  ".el-message-box__headerbtn",
  ".layui-layer-close",
  'button[aria-label="Close"]',
  'button[aria-label="关闭"]',
].join(", ");

async function findDismissibleStartupDialog(page) {
  for (const frame of page.frames()) {
    const dialogs = frame.locator(STARTUP_DIALOG_SELECTOR);
    const count = await dialogs.count();
    for (let index = 0; index < count; index += 1) {
      const dialog = dialogs.nth(index);
      if (!await dialog.isVisible().catch(() => false)) continue;
      const closeIcon = await firstVisible(dialog.locator(STARTUP_DIALOG_CLOSE_SELECTOR));
      const closeText = closeIcon
        ? null
        : await firstVisible(dialog.getByText("关闭", { exact: true }));
      return { dialog, close: closeIcon ?? closeText };
    }
  }
  return null;
}

export async function dismissStartupDialogs(page, { maxDialogs = 5 } = {}) {
  let closed = 0;
  while (closed < maxDialogs) {
    const candidate = await findDismissibleStartupDialog(page);
    if (!candidate) return closed;
    if (!candidate.close) {
      throw new Error("ERP 已登录，但启动弹窗没有安全关闭按钮");
    }
    await candidate.close.click();
    await candidate.dialog.waitFor({ state: "hidden", timeout: 10_000 });
    closed += 1;
  }
  if (await findDismissibleStartupDialog(page)) {
    throw new Error(`ERP 启动弹窗超过 ${maxDialogs} 层，已停止自动化`);
  }
  return closed;
}

export async function fillOrderAndClickSearch(page, orderNo) {
  const inputContext = await findInputContext(
    page,
    [/系统单号\/内部单号\/平台单号\/快递单号/, /平台单号|内部单号/],
  );
  if (!inputContext) throw new Error("无法定位订单查询输入框");

  await inputContext.locator.fill(orderNo);
  const actualOrderNo = await inputContext.locator.inputValue();
  if (actualOrderNo.trim() !== orderNo.trim()) {
    throw new Error("订单号未正确写入查询输入框");
  }

  // 触发查询:先按人工操作——在输入框回车确认(用户亲述第3步);找不到 /trade/search 响应再兜底点查询按钮。
  // 统一等 POST /trade/search 响应 + loading 消失,拿结果条数。
  const searchResponseMatcher = (response) => {
    try {
      return response.request().method() === "POST"
        && new URL(response.url()).pathname === "/trade/search";
    } catch { return false; }
  };
  const awaitSearchResult = async (responsePromise) => {
    const response = await responsePromise;
    if (!response.ok()) throw new Error(`ERP 订单查询请求失败：HTTP ${response.status()}`);
    const payload = await response.json().catch(() => null);
    const expectedRowCount = Array.isArray(payload?.data?.list) ? payload.data.list.length : 0;
    // 诊断:定位"脚本搜0条但手动能搜到"的脏单号问题。JSON.stringify 显性化转义字符,codes 是每字符十六进制码。
    // 若 len 比肉眼多、或 codes 里出现 200b/feff 等 → Excel单元格带零宽字符,order-workbook 读时只trim没剥掉。
    const codes = [...orderNo].map((c) => c.charCodeAt(0).toString(16)).join(" ");
    console.error(`[补发诊断·订单搜索] 单号=${JSON.stringify(orderNo)} len=${orderNo.length} → data.list 条数=${expectedRowCount}`);
    if (expectedRowCount === 0) console.error(`[补发诊断·订单搜索] 搜到0条,单号字符码: ${codes}`);
    await inputContext.frame
      .locator("#tradeNew_manage .el-loading-mask")
      .first()
      .waitFor({ state: "hidden", timeout: 30_000 }).catch(() => {});
    return { ...inputContext, expectedRowCount };
  };

  // 路径1:回车触发(人工操作方式)。输入框已 fill,按 Enter。
  try {
    const responsePromise = page.waitForResponse(searchResponseMatcher, { timeout: 12_000 });
    await inputContext.locator.press("Enter");
    return await awaitSearchResult(responsePromise);
  } catch (enterErr) {
    // 回车没触发查询请求,回退到点击查询按钮
  }

  // 路径2兜底:点查询按钮。兼容两种 ERP 版式——
  //   新版(实测):<button class="el-button el-button--primary" trackname="trade_new62_ChaXun"><span>查询</span>
  //   旧版:<a class="btn btn-search">查询</a>
  const buttonSelectors = [
    'button[trackname="trade_new62_ChaXun"]',       // 新版最稳:trackname=查询拼音
    'button.el-button--primary',                    // 新版兜底:主按钮里筛文字
    'a.btn.btn-search',                             // 旧版
  ];
  for (const sel of buttonSelectors) {
    const searches = inputContext.frame.locator(sel);
    const count = await searches.count();
    for (let index = 0; index < count; index += 1) {
      const search = searches.nth(index);
      if (!await search.isVisible().catch(() => false)) continue;
      if ((await search.innerText()).replace(/\s+/g, "") !== "查询") continue;
      const responsePromise = page.waitForResponse(searchResponseMatcher, { timeout: 30_000 });
      await search.click();
      return await awaitSearchResult(responsePromise);
    }
  }

  throw new Error("无法触发订单查询(回车无响应,且未找到查询按钮)");
}

/**
 * 等结果行渲染齐。原来死等 `行数===expectedRowCount`(API总条数),但页面懒渲染/虚拟滚动下 DOM 行数
 * 常不等于 API 总数(如 API 说17、当前只渲染16)→ 永远不相等抛错。改为:滚动结果表到底触发全部渲染,
 * 等【行数稳定】(连续两次 count 相同且>0)即返回,不强求等于 expectedRowCount(该值仅作日志参考)。
 */
export async function waitForOrderResultRows(frame, expectedRowCount, {
  attempts = 75,
  pause = () => delay(400),
} = {}) {
  const rows = frame.locator(".module-trade-list-item.module-list-item-inpage.js-checkbox-row");
  // 滚动结果表容器到底,触发懒加载行全部渲染(每页可容纳很多行,目标行可能未渲染)
  const scrollToBottom = async () => {
    await frame.evaluate(() => {
      const sels = [".module-trade-list", ".el-table__body-wrapper", ".trade-list-body", ".module-list-inpage"];
      for (const s of sels) {
        for (const el of document.querySelectorAll(s)) {
          if (el.scrollHeight > el.clientHeight) el.scrollTop = el.scrollHeight;
        }
      }
      window.scrollTo(0, document.body.scrollHeight);
    }).catch(() => {});
  };
  let prev = -1, stable = 0;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    await scrollToBottom();
    const count = await rows.count();
    if (count > 0 && count === prev) {
      stable += 1;
      if (stable >= 2) {   // 连续两次一致 = 渲染稳定
        if (count !== expectedRowCount) {
          console.error(`[补发诊断·结果行] API 报 ${expectedRowCount} 条,页面渲染 ${count} 行(以页面为准)`);
        }
        return rows;
      }
    } else {
      stable = 0;
    }
    prev = count;
    if (attempt < attempts - 1) await pause();
  }
  if (prev > 0) return rows;   // 到底了没完全稳定也放行(有行即可,匹配靠状态)
  throw new Error(`订单查询无结果行(API 报 ${expectedRowCount} 条)`);
}

async function waitUntil(check, timeout, message) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await check();
    if (value) return value;
    await delay(400);
  }
  throw new Error(message);
}

export async function openResendMenu(page, matchingRow) {
  const staleMenu = await findText(page, "批量创建补发单");
  if (staleMenu) {
    await page.keyboard.press("Escape").catch(() => {});
    await staleMenu.locator.waitFor({ state: "hidden", timeout: 2_000 }).catch(() => {});
  }

  const detail = await firstVisible(matchingRow.getByText("明细", { exact: true }))
    ?? await firstVisible(matchingRow.getByText("商品明细", { exact: true }));
  if (!detail) throw new Error("严格匹配订单行中没有找到商品明细右键目标");
  if (typeof detail.scrollIntoViewIfNeeded === "function") {
    await detail.scrollIntoViewIfNeeded();
  }
  await detail.click({ button: "right" });
  return waitUntil(
    () => findText(page, "批量创建补发单"),
    10_000,
    "匹配订单行右键后没有出现批量创建补发单菜单",
  );
}

const DUPLICATE_RESEND_MESSAGE = "已经补发过了，确定要补发吗？";
// 优化1弹窗兜底:选了不可补发状态的行点补发时,ERP 弹此提示。检测到即判该单不可补发(标红跳过)。
const NOT_RESHIPPABLE_MESSAGE = "请选择卖家已发货或交易成功或交易关闭的订单进行补发";

// 检测"不可补发状态"提示弹窗(仿 findDuplicateResendDialog)。返回 {frame,dialog} 或 null。
async function findNotReshippableDialog(page) {
  for (const frame of page.frames()) {
    const dialogs = frame
      .locator(".el-message-box, .el-dialog, .el-message")
      .filter({ hasText: NOT_RESHIPPABLE_MESSAGE });
    const dialog = await firstVisible(dialogs);
    if (dialog) return { frame, dialog };
  }
  return null;
}

async function findDuplicateResendDialog(page) {
  for (const frame of page.frames()) {
    const dialogs = frame
      .locator(".el-message-box, .el-dialog")
      .filter({ hasText: DUPLICATE_RESEND_MESSAGE });
    const dialog = await firstVisible(dialogs);
    if (dialog) return { frame, dialog };
  }
  return null;
}

export async function waitForAddProductDialog(page, {
  timeout = 45_000,
  pause = () => delay(400),
} = {}) {
  const deadline = Date.now() + timeout;
  let duplicateConfirmed = false;
  while (Date.now() < deadline) {
    const duplicate = await findDuplicateResendDialog(page);
    if (duplicate) {
      const confirm = await firstVisible(
        duplicate.dialog.getByText("确定继续", { exact: true }),
      );
      if (!confirm) throw new Error("重复补发警告中没有确定继续按钮");
      await confirm.click();
      await duplicate.dialog.waitFor({ state: "hidden", timeout: 20_000 });
      duplicateConfirmed = true;
      continue;
    }

    for (const frame of page.frames()) {
      const dialogs = frame.locator(".el-dialog").filter({ hasText: "添加商品" });
      const dialog = await firstVisible(dialogs);
      if (dialog) return { frame, dialog };
    }
    await pause();
  }
  throw new Error(duplicateConfirmed
    ? "已点击确定继续，但没有出现添加商品弹窗"
    : "批量创建补发单后没有出现添加商品弹窗");
}

function report(progress, stage, message) {
  progress?.({ stage, message });
}

export class ErpBrowserRunner {
  constructor({
    chromium = defaultChromium,
    userDataDir,
    timeout = 20_000,
  }) {
    this.chromium = chromium;
    this.userDataDir = userDataDir;
    this.timeout = timeout;
    this.context = null;
    this.page = null;
  }

  async start(credentials, progress) {
    if (!this.context) {
      report(progress, "browser", "正在打开 Microsoft Edge");
      this.context = await this.chromium.launchPersistentContext(this.userDataDir, {
        channel: "msedge",
        headless: false,
        viewport: null,
        args: ["--start-maximized"],
      });
      this.page = this.context.pages()[0] ?? await this.context.newPage();
      this.page.setDefaultTimeout(this.timeout);
    }
    await this.page.goto(ERP_URL, { waitUntil: "domcontentloaded" });
    await this.ensureLoggedIn(credentials, progress);
    await this.openOrderManagement(progress);
  }

  async ensureLoggedIn(credentials, progress) {
    const state = await waitForErpAuthState(this.page);
    if (state.type === "login") {
      report(progress, "login", "正在填写 ERP 登录信息");
      const { frame, company, account, password, login } = state.form;
      await company.fill(credentials.company);
      await account.fill(credentials.account);
      await password.fill(credentials.password);
      const agreement = await firstVisible(frame.locator("#reading"));
      if (!agreement) throw new Error("无法定位我已阅读并同意复选框");
      if (!await agreement.isChecked()) {
        const agreementLabel = await firstVisible(frame.locator('label[for="reading"]'));
        if (agreementLabel) await agreementLabel.click();
        else await agreement.check();
      }
      if (!await agreement.isChecked()) throw new Error("无法勾选我已阅读并同意");

      await login.click();
      report(progress, "login", "等待 ERP 登录完成；如有验证码请在浏览器中处理");
      await waitUntil(
        () => findAuthenticatedMarker(this.page),
        120_000,
        "ERP 登录超时，未检测到订单管理",
      );
    } else {
      report(progress, "login", "检测到已保存的 ERP 登录会话");
    }

    const closedDialogs = await dismissStartupDialogs(this.page);
    if (closedDialogs) {
      report(progress, "login", `已关闭 ${closedDialogs} 个 ERP 启动弹窗`);
    }
    await waitUntil(
      () => findText(this.page, "订单管理"),
      30_000,
      "ERP 已登录，但关闭启动弹窗后仍无法看到订单管理",
    );
  }

  // 清障:等全屏 loading 遮罩消失 + 关掉授权到期等弹窗(shopAuthorizeDlgWinHandle/el-dialog),
  // 否则遮罩/弹窗拦截点击 → intercepts pointer events。每个关键点击前调。
  async clearBlockers() {
    // 1) 等所有全屏 loading 遮罩消失(最多8s)
    try {
      await this.page.waitForFunction(
        () => !document.querySelector('.el-loading-mask.is-fullscreen'),
        { timeout: 8_000 },
      );
    } catch (_) {}
    // 2) 关授权/启动弹窗:优先点关闭图标(用户给的 el-dialog__close),其次通用启动弹窗清理
    for (let i = 0; i < 5; i += 1) {
      const closeIcon = this.page.locator('.el-dialog__wrapper .el-dialog__close, .shopAuthorizeDlgWinHandle .el-dialog__close').first();
      if (await closeIcon.isVisible().catch(() => false)) {
        await closeIcon.click().catch(() => {});
        await this.page.waitForTimeout(500);
        continue;
      }
      break;
    }
    try { await dismissStartupDialogs(this.page); } catch (_) {}
    // 3) 再等一次遮罩(关弹窗可能触发新加载)
    try {
      await this.page.waitForFunction(
        () => !document.querySelector('.el-loading-mask.is-fullscreen'),
        { timeout: 8_000 },
      );
    } catch (_) {}
  }

  async openOrderManagement(progress) {
    report(progress, "navigate", "正在进入订单管理");
    await this.clearBlockers();
    const orderManagement = await findText(this.page, "订单管理");
    if (!orderManagement) throw new Error("无法定位订单管理");
    await orderManagement.locator.click();
    await waitUntil(
      () => findText(this.page, "平台单号"),
      30_000,
      "订单管理页面未加载平台单号筛选项",
    );
    await this.clearBlockers();   // 点平台单号前再清一次(进订单管理常触发遮罩/授权弹窗)
    const platformOrder = await findText(this.page, "平台单号");
    await platformOrder.locator.click();
  }

  async processOrder(order, progress) {
    report(progress, "query", `正在查询 Excel 第 ${order.row} 行订单`);
    await this.clearBlockers();   // 查询前清遮罩/弹窗(逐单处理中弹窗可能随时冒出)
    const queryContext = await fillOrderAndClickSearch(this.page, order.orderNo);
    report(progress, "query", `订单查询已完成，返回 ${queryContext.expectedRowCount} 条结果`);

    if (queryContext.expectedRowCount === 0) {
      return { ok: false, code: "ORDER_NOT_FOUND", message: "没有找到完全匹配的订单" };
    }
    const rows = await waitForOrderResultRows(queryContext.frame, queryContext.expectedRowCount);
    const resultContext = { frame: queryContext.frame, rows };

    report(progress, "match", "正在按订单状态挑选可补发行(交易成功/已发货/交易关闭)");
    const matchingRow = await pickReshipableRow(resultContext.rows);
    if (!matchingRow) {
      return { ok: false, code: "ORDER_NOT_RESHIPPABLE", message: "结果行中无可补发状态(交易成功/已发货/交易关闭)的订单" };
    }

    report(progress, "resend", "已挑中可补发行，正在右键该行商品明细");
    const createResend = await openResendMenu(this.page, matchingRow);
    report(progress, "resend", "匹配行右键菜单已出现，正在点击批量创建补发单");
    await createResend.locator.click();
    await createResend.locator.waitFor({ state: "hidden", timeout: 5_000 }).catch(() => {
      throw new Error("批量创建补发单菜单点击后没有关闭");
    });

    // 优化1弹窗兜底:若挑中的行状态不可补发,ERP 弹"请选择…的订单进行补发"。检测到即标红跳过(不抛错、不中止)。
    const notReshippable = await findNotReshippableDialog(this.page);
    if (notReshippable) {
      await notReshippable.dialog.getByText("确定", { exact: true }).first().click().catch(() => {});
      await this.page.keyboard.press("Escape").catch(() => {});
      return { ok: false, code: "ORDER_NOT_RESHIPPABLE", message: "订单状态不可补发(非交易成功/已发货/交易关闭)，已跳过" };
    }

    const next = await waitForAddProductDialog(this.page);

    report(progress, "product", "正在查询并选择补发商品");
    const merchantCode = await firstVisible(next.dialog.getByPlaceholder("主商家编码"));
    if (!merchantCode) throw new Error("添加商品弹窗中没有主商家编码输入框");
    const productSearch = await firstVisible(next.dialog.getByText("查询", { exact: true }));
    if (!productSearch) throw new Error("添加商品弹窗中没有查询按钮");
    // 用给定编码填搜索框+点查询,拿到首个结果行(短等待);无结果返回 null(不抛错)。带诊断:打出搜到几行。
    const searchProduct = async (code) => {
      await merchantCode.fill("");
      await merchantCode.fill(code);
      await productSearch.click();
      let row = null;
      try {
        row = await waitUntil(async () => {
          const rows = next.dialog.locator(".el-table__body-wrapper tbody tr");
          return (await rows.count()) ? rows.first() : null;
        }, 12_000, "无结果");
      } catch (_) { row = null; }
      const cnt = await next.dialog.locator(".el-table__body-wrapper tbody tr").count().catch(() => -1);
      console.error(`[补发诊断·商品搜索] 编码「${code}」→ 结果行数=${cnt}`);
      return row;
    };

    // 优化2a(先精确·再降级):快麦编码里 *N 有两义——可能是编码固有部分(如"001白单手喷+001滤芯*5"整体是一个编码),
    // 也可能是数量后缀(如"红球*2")。故【先用整串精确搜】——命中即用它(数量1),这是最常见且正确的情形;
    // 只有整串搜不到、且形如"<编码>*<纯数字>"时,才拆 * 降级(用 * 前编码搜、数量填 * 后数字)。
    const raw = (order.merchantCode || "").trim();
    const starMatch = /^(.*?)\s*\*\s*(\d+)\s*$/.exec(raw);
    let firstRow = await searchProduct(raw);
    let qty = "1";
    if (!firstRow && starMatch) {
      report(progress, "product", `完整编码「${raw}」未命中,拆 * 降级搜「${starMatch[1].trim()}」数量 ${starMatch[2]}`);
      console.error(`[补发诊断·2a降级] 整串未命中,降级搜 * 前编码「${starMatch[1].trim()}」`);
      firstRow = await searchProduct(starMatch[1].trim());
      qty = starMatch[2];
    }
    // 优化2b:两种搜法都无结果→不抛错(原来抛错→整体中止)。返回 ok:false 让上层标红该行、继续下一行。
    if (!firstRow) {
      await this.page.keyboard.press("Escape").catch(() => {});
      return { ok: false, code: "MERCHANT_CODE_NOT_FOUND", message: `快麦无此编码「${raw}」，已跳过` };
    }
    const checkbox = await firstVisible(firstRow.locator(".el-checkbox"));
    if (!checkbox) throw new Error("商品第一行没有选择框");
    await checkbox.click();
    // 数量框:排除复选框的隐藏 input(el-checkbox__original,不可见)。取可见的数量输入框——
    // 优先 el-input 里的 input(数量列是 el-input),再兜底任意可见非checkbox input。
    let quantity = await firstVisible(firstRow.locator(".el-input__inner"));
    if (!quantity) {
      quantity = await firstVisible(firstRow.locator("input:not(.el-checkbox__original)"));
    }
    if (!quantity) throw new Error("商品第一行没有数量输入框");
    await quantity.fill(qty);   // 优化2a:填解析出的数量(默认1)

    const footer = next.dialog.locator(".el-dialog__footer");
    const confirm = await firstVisible(footer.getByText("确定", { exact: true }));
    if (!confirm) throw new Error("添加商品弹窗没有确定按钮");
    await confirm.click();
    await next.dialog.waitFor({ state: "hidden", timeout: 20_000 });
    return { ok: true, code: "ORDER_COMPLETED", message: "补发商品已确认" };
  }
}

export { ERP_URL };
