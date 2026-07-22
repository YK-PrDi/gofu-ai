import path from "node:path";

import { DOMParser, XMLSerializer } from "@xmldom/xmldom";

export const NS = {
  main: "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
  rel: "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
  pkgRel: "http://schemas.openxmlformats.org/package/2006/relationships",
  xdr: "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing",
  a: "http://schemas.openxmlformats.org/drawingml/2006/main",
};

export function parseXml(text) {
  return new DOMParser().parseFromString(text, "application/xml");
}

export function serializeXml(document) {
  return new XMLSerializer().serializeToString(document);
}

export function decodeCell(cellXml, sharedStrings) {
  const document = parseXml(`<root xmlns="${NS.main}">${cellXml}</root>`);
  const cell = document.documentElement.firstChild;
  const type = cell.getAttribute("t");
  const formula = Array.from(cell.childNodes).find((node) => node.localName === "f");
  if (formula) return `=${formula.textContent}`;

  if (type === "inlineStr") {
    return Array.from(cell.getElementsByTagNameNS(NS.main, "t"), (node) => node.textContent).join("");
  }

  const value = Array.from(cell.childNodes).find((node) => node.localName === "v")?.textContent ?? "";
  return type === "s" ? sharedStrings[Number(value)] ?? "" : value;
}

export function findFirstSafeRow(rows, startRow, maxRow) {
  for (let row = startRow; row <= maxRow; row += 1) {
    const values = rows.get(row) ?? { B: "", C: "", D: "", H: "" };
    if ([values.B, values.C, values.D, values.H].every((value) => value.trim() === "")) {
      return row;
    }
  }
  throw new Error("目标表不存在 B/C/D/H 全空的安全行");
}

export function nextNumericId(ids) {
  return Math.max(0, ...ids) + 1;
}

export function normalizeZipTarget(baseEntry, target) {
  return path.posix.normalize(path.posix.join(path.posix.dirname(baseEntry), target));
}
