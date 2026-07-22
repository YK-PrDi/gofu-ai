import path from "node:path";

import { unzipSync, zipSync } from "fflate";

import { NS, normalizeZipTarget, parseXml, serializeXml } from "./openxml.js";

const decoder = new TextDecoder();
const encoder = new TextEncoder();
const RED = "FFFF0000";

function text(archive, entry) {
  const bytes = archive[entry];
  if (!bytes) throw new Error(`XLSX 缺少条目：${entry}`);
  return decoder.decode(bytes);
}

function elements(parent, localName) {
  return Array.from(parent.getElementsByTagName("*")).filter((node) => node.localName === localName);
}

function directChild(parent, localName) {
  return Array.from(parent.childNodes).find(
    (node) => node.nodeType === 1 && node.localName === localName,
  ) ?? null;
}

function relationshipEntry(entry) {
  return path.posix.join(path.posix.dirname(entry), "_rels", `${path.posix.basename(entry)}.rels`);
}

function resolveSheet(archive) {
  const workbookEntry = "xl/workbook.xml";
  const workbook = parseXml(text(archive, workbookEntry));
  const sheets = elements(workbook, "sheet");
  const selected = sheets.find((sheet) => sheet.getAttribute("name") === "补发表") ?? sheets[0];
  if (!selected) throw new Error("工作簿中没有工作表");
  const id = selected.getAttributeNS(NS.rel, "id") || selected.getAttribute("r:id");
  const relationships = parseXml(text(archive, relationshipEntry(workbookEntry)));
  const relationship = elements(relationships, "Relationship")
    .find((item) => item.getAttribute("Id") === id);
  if (!relationship) throw new Error("无法定位补发表工作表");
  return normalizeZipTarget(workbookEntry, relationship.getAttribute("Target"));
}

function sharedStrings(archive) {
  if (!archive["xl/sharedStrings.xml"]) return [];
  const document = parseXml(text(archive, "xl/sharedStrings.xml"));
  return elements(document, "si").map((item) =>
    elements(item, "t").map((node) => node.textContent).join(""),
  );
}

function cellValue(cell, strings) {
  if (!cell) return "";
  if (cell.getAttribute("t") === "inlineStr") {
    return elements(cell, "t").map((node) => node.textContent).join("");
  }
  const value = directChild(cell, "v")?.textContent ?? "";
  return cell.getAttribute("t") === "s" ? strings[Number(value)] ?? "" : value;
}

function getCell(row, reference) {
  return Array.from(row.childNodes).find(
    (node) => node.nodeType === 1 && node.localName === "c" && node.getAttribute("r") === reference,
  ) ?? null;
}

function columnFromReference(reference) {
  return String(reference).match(/^[A-Z]+/)?.[0] ?? "";
}

function columnNumber(column) {
  return [...column].reduce((value, character) => value * 26 + character.charCodeAt(0) - 64, 0);
}

function ensureCell(document, row, column, rowNumber) {
  const reference = `${column}${rowNumber}`;
  const existing = getCell(row, reference);
  if (existing) return existing;
  const cell = document.createElementNS(NS.main, "c");
  cell.setAttribute("r", reference);
  const next = Array.from(row.childNodes).find((node) => {
    if (node.nodeType !== 1 || node.localName !== "c") return false;
    const nextColumn = node.getAttribute("r").match(/^[A-Z]+/)?.[0] ?? "";
    return columnNumber(nextColumn) > columnNumber(column);
  });
  row.insertBefore(cell, next ?? null);
  return cell;
}

function createDefaultStyles() {
  return parseXml(`<?xml version="1.0" encoding="UTF-8"?>
    <styleSheet xmlns="${NS.main}">
      <fonts count="1"><font/></fonts>
      <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
      <borders count="1"><border/></borders>
      <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
      <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
    </styleSheet>`);
}

function ensureStylesRelationship(archive) {
  const entry = "xl/_rels/workbook.xml.rels";
  const document = parseXml(text(archive, entry));
  if (!elements(document, "Relationship").some((item) => item.getAttribute("Type").endsWith("/styles"))) {
    const ids = elements(document, "Relationship")
      .map((item) => Number(item.getAttribute("Id").match(/\d+$/)?.[0] ?? 0));
    const relationship = document.createElementNS(NS.pkgRel, "Relationship");
    relationship.setAttribute("Id", `rId${Math.max(0, ...ids) + 1}`);
    relationship.setAttribute("Type", `${NS.rel}/styles`);
    relationship.setAttribute("Target", "styles.xml");
    document.documentElement.appendChild(relationship);
    archive[entry] = encoder.encode(serializeXml(document));
  }
}

function ensureStylesContentType(archive) {
  const entry = "[Content_Types].xml";
  const document = parseXml(text(archive, entry));
  if (!elements(document, "Override").some((item) => item.getAttribute("PartName") === "/xl/styles.xml")) {
    const override = document.createElementNS(
      "http://schemas.openxmlformats.org/package/2006/content-types",
      "Override",
    );
    override.setAttribute("PartName", "/xl/styles.xml");
    override.setAttribute("ContentType", "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml");
    document.documentElement.appendChild(override);
    archive[entry] = encoder.encode(serializeXml(document));
  }
}

function addRedStyles(stylesDocument, baseStyleIds) {
  const fills = elements(stylesDocument, "fills")[0];
  const fillId = Array.from(fills.childNodes).filter((node) => node.nodeType === 1).length;
  const fill = stylesDocument.createElementNS(NS.main, "fill");
  const pattern = stylesDocument.createElementNS(NS.main, "patternFill");
  pattern.setAttribute("patternType", "solid");
  const foreground = stylesDocument.createElementNS(NS.main, "fgColor");
  foreground.setAttribute("rgb", RED);
  const background = stylesDocument.createElementNS(NS.main, "bgColor");
  background.setAttribute("indexed", "64");
  pattern.appendChild(foreground);
  pattern.appendChild(background);
  fill.appendChild(pattern);
  fills.appendChild(fill);
  fills.setAttribute("count", String(fillId + 1));

  const cellXfs = elements(stylesDocument, "cellXfs")[0];
  const existing = Array.from(cellXfs.childNodes).filter((node) => node.nodeType === 1);
  const mapping = new Map();
  for (const baseStyle of baseStyleIds) {
    const clone = (existing[baseStyle] ?? existing[0]).cloneNode(true);
    clone.setAttribute("fillId", String(fillId));
    clone.setAttribute("applyFill", "1");
    const styleId = Array.from(cellXfs.childNodes).filter((node) => node.nodeType === 1).length;
    cellXfs.appendChild(clone);
    mapping.set(baseStyle, styleId);
  }
  cellXfs.setAttribute("count", String(Array.from(cellXfs.childNodes).filter((node) => node.nodeType === 1).length));
  return mapping;
}

export function readOrderRows(bytes) {
  const archive = unzipSync(new Uint8Array(bytes));
  const sheet = parseXml(text(archive, resolveSheet(archive)));
  const strings = sharedStrings(archive);
  const header = elements(sheet, "row").find((row) => Number(row.getAttribute("r")) === 1);
  const statusColumn = header
    ? Array.from(header.childNodes)
      .filter((node) => node.nodeType === 1 && node.localName === "c")
      .find((cell) => cellValue(cell, strings).trim() === "补发状态")
    : null;
  const statusColumnName = columnFromReference(statusColumn?.getAttribute("r"));
  const output = [];
  for (const row of elements(sheet, "row")) {
    const number = Number(row.getAttribute("r"));
    if (number < 2) continue;
    const orderNo = cellValue(getCell(row, `B${number}`), strings).trim();
    if (!orderNo) continue;
    output.push({
      row: number,
      orderNo,
      merchantCode: cellValue(getCell(row, `D${number}`), strings).trim(),
      reshipStatus: statusColumnName
        ? cellValue(getCell(row, `${statusColumnName}${number}`), strings).trim()
        : "",
    });
  }
  return output;
}

export function markOrderRowRed(bytes, { row: rowNumber, orderNo }) {
  const archive = unzipSync(new Uint8Array(bytes));
  const sheetEntry = resolveSheet(archive);
  const sheet = parseXml(text(archive, sheetEntry));
  const row = elements(sheet, "row").find((item) => Number(item.getAttribute("r")) === rowNumber);
  if (!row) throw new Error(`补发表不存在第 ${rowNumber} 行`);
  const currentOrder = cellValue(getCell(row, `B${rowNumber}`), sharedStrings(archive)).trim();
  if (currentOrder !== orderNo) throw new Error(`第 ${rowNumber} 行订单号已经变化，拒绝标红`);

  const cells = [];
  for (let number = 1; number <= 13; number += 1) {
    let value = number;
    let column = "";
    while (value > 0) {
      value -= 1;
      column = String.fromCharCode(65 + (value % 26)) + column;
      value = Math.floor(value / 26);
    }
    cells.push(ensureCell(sheet, row, column, rowNumber));
  }

  const styles = archive["xl/styles.xml"]
    ? parseXml(text(archive, "xl/styles.xml"))
    : createDefaultStyles();
  const baseStyles = new Set(cells.map((cell) => Number(cell.getAttribute("s") || 0)));
  const redStyles = addRedStyles(styles, baseStyles);
  for (const cell of cells) {
    cell.setAttribute("s", String(redStyles.get(Number(cell.getAttribute("s") || 0))));
  }

  archive[sheetEntry] = encoder.encode(serializeXml(sheet));
  archive["xl/styles.xml"] = encoder.encode(serializeXml(styles));
  ensureStylesRelationship(archive);
  ensureStylesContentType(archive);
  return Buffer.from(zipSync(archive, { level: 6 }));
}
