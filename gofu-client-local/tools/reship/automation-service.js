import fs from "node:fs/promises";

import {
  migrateRemarkArchive as defaultMigrateRemarkArchive,
  readRemarkRecords as defaultReadRemarkRecords,
} from "./migrate-image-note.js";
import { markOrderRowRed as defaultMarkOrderRowRed, readOrderRows as defaultReadOrderRows } from "./order-workbook.js";

export async function writeFileAtomically(filePath, bytes) {
  const temporaryPath = `${filePath}.${process.pid}.tmp.xlsx`;
  try {
    await fs.writeFile(temporaryPath, bytes);
    await fs.rm(filePath, { force: true });
    await fs.rename(temporaryPath, filePath);
  } catch (error) {
    await fs.rm(temporaryPath, { force: true });
    throw new Error(`保存 Excel 失败：${error.message}`, { cause: error });
  }
}

function maskedOrder(orderNo) {
  const value = String(orderNo);
  return value.length <= 6 ? value : `***${value.slice(-6)}`;
}

export class AutomationService {
  constructor({
    runner,
    wpsGateway,
    readFile = fs.readFile,
    readOrderRows = defaultReadOrderRows,
    readRemarkRecords,
    migrateRemarkArchive,
    readImageNoteRecords,
    migrateImageNoteArchive,
    markOrderRowRed = defaultMarkOrderRowRed,
    writeFileAtomically: persist = writeFileAtomically,
  }) {
    this.runner = runner;
    this.wpsGateway = wpsGateway;
    this.readFile = readFile;
    this.readOrderRows = readOrderRows;
    this.readRemarkRecords = readRemarkRecords ?? readImageNoteRecords ?? defaultReadRemarkRecords;
    this.migrateRemarkArchive = migrateRemarkArchive ?? migrateImageNoteArchive ?? defaultMigrateRemarkArchive;
    this.markOrderRowRed = markOrderRowRed;
    this.persist = persist;
  }

  async run(request, onProgress) {
    if ((request.source.kind === "wps" || request.target.kind === "wps") && !this.wpsGateway) {
      throw new Error("WPS 云表网关不可用");
    }

    const sourceBytes = request.source.kind === "wps"
      ? await this.wpsGateway.downloadLatest(request.source.value)
      : await this.readFile(request.source.value);
    const orders = this.readOrderRows(sourceBytes);
    const alreadyResentRows = new Set(
      orders.filter((order) => order.reshipStatus === "已补发").map((order) => order.row),
    );
    const summary = { processed: 0, success: 0, failed: 0 };
    onProgress?.({ stage: "start", message: `读取到 ${orders.length} 条订单`, summary: { ...summary } });

    const remarkRecords = this.readRemarkRecords(sourceBytes)
      .filter((record) => !alreadyResentRows.has(record.row));
    const remarkRows = new Set(remarkRecords.map((record) => record.row));
    if (request.target.kind === "wps") {
      const appendRemark = this.wpsGateway.appendRemark?.bind(this.wpsGateway)
        ?? this.wpsGateway.appendImageNote?.bind(this.wpsGateway);
      if (!appendRemark && remarkRecords.length > 0) throw new Error("WPS 备注追加接口不可用");
      for (const record of remarkRecords) {
        const remarkResult = await appendRemark(request.target.value, record);
        onProgress?.({
          stage: "remark",
          message: remarkResult.message ?? `源第 ${record.row} 行备注已处理`,
          row: record.row,
          summary: { ...summary },
        });
      }
    } else if (remarkRecords.length > 0) {
      const targetBytes = await this.readFile(request.target.value);
      const migratedBytes = this.migrateRemarkArchive(sourceBytes, targetBytes);
      if (!Buffer.from(migratedBytes).equals(Buffer.from(targetBytes))) {
        await this.persist(request.target.value, migratedBytes);
        onProgress?.({
          stage: "remark",
          message: `已向本地 GOFU 补发表增量迁移 ${remarkRecords.length} 条备注订单`,
          summary: { ...summary },
        });
      }
    }

    if (orders.some((order) => !alreadyResentRows.has(order.row) && !remarkRows.has(order.row))) {
      await this.runner.start(request.erp, (event) => onProgress?.({ ...event, summary: { ...summary } }));
    }

    for (const order of orders) {
      onProgress?.({
        stage: "order",
        message: `正在处理第 ${order.row} 行 ${maskedOrder(order.orderNo)}`,
        row: order.row,
        summary: { ...summary },
      });

      let result;
      if (alreadyResentRows.has(order.row)) {
        result = { ok: true, code: "ALREADY_RESENT_SKIPPED", message: "补发状态为已补发，跳过" };
      } else if (remarkRows.has(order.row)) {
        result = { ok: true, code: "REMARK_ROUTED", message: "备注行已转 GOFU，跳过 ERP" };
      } else if (!order.merchantCode) {
        result = { ok: false, code: "MERCHANT_CODE_EMPTY", message: "主商家编码为空" };
      } else {
        try {
          result = await this.runner.processOrder(order, (event) => onProgress?.({
            ...event,
            row: order.row,
            orderNo: maskedOrder(order.orderNo),
            summary: { ...summary },
          }));
        } catch (error) {
          return {
            ok: false,
            code: "AUTOMATION_ABORTED",
            message: error.message,
            summary,
          };
        }
      }

      summary.processed += 1;
      if (result.ok) {
        summary.success += 1;
      } else {
        if (request.source.kind === "wps") {
          await this.wpsGateway.markOrderRowRed(request.source.value, {
            row: order.row,
            orderNo: order.orderNo,
          });
        } else {
          const latestBytes = await this.readFile(request.source.value);
          const marked = this.markOrderRowRed(latestBytes, {
            row: order.row,
            orderNo: order.orderNo,
          });
          await this.persist(request.source.value, marked);
        }
        summary.failed += 1;
      }
      onProgress?.({
        stage: result.ok ? "success" : "failed",
        message: `第 ${order.row} 行：${result.message}`,
        row: order.row,
        orderNo: maskedOrder(order.orderNo),
        code: result.code,
        summary: { ...summary },
      });
    }

    return {
      ok: true,
      code: "AUTOMATION_COMPLETED",
      message: `处理完成：成功 ${summary.success}，失败 ${summary.failed}`,
      summary,
    };
  }
}
