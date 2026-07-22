import crypto from "node:crypto";
import path from "node:path";

import { unzipSync, zipSync } from "fflate";

import {
  NS,
  findFirstSafeRow,
  nextNumericId,
  normalizeZipTarget,
  parseXml,
  serializeXml,
} from "./openxml.js";

const decoder = new TextDecoder();
const encoder = new TextEncoder();

function getText(archive, entry) {
  const bytes = archive[entry];
  if (!bytes) throw new Error(`XLSX 缺少条目：${entry}`);
  return decoder.decode(bytes);
}

function setText(archive, entry, text) {
  archive[entry] = encoder.encode(text);
}

function elementsByLocalName(parent, name) {
  return Array.from(parent.getElementsByTagName("*")).filter((node) => node.localName === name);
}

function childByLocalName(parent, name) {
  return Array.from(parent.childNodes).find((node) => node.nodeType === 1 && node.localName === name) ?? null;
}

function relationshipEntry(partEntry) {
  return path.posix.join(path.posix.dirname(partEntry), "_rels", `${path.posix.basename(partEntry)}.rels`);
}

function findRelationship(document, id) {
  return elementsByLocalName(document, "Relationship").find((item) => item.getAttribute("Id") === id) ?? null;
}

function resolveSheetEntry(archive, preferredNames) {
  const workbookEntry = "xl/workbook.xml";
  const workbook = parseXml(getText(archive, workbookEntry));
  const sheets = elementsByLocalName(workbook, "sheet");
  const sheet = preferredNames
    .map((name) => sheets.find((item) => item.getAttribute("name") === name))
    .find(Boolean) ?? sheets[0];
  if (!sheet) throw new Error("工作簿中没有工作表");
  const relationshipId = sheet.getAttributeNS(NS.rel, "id") || sheet.getAttribute("r:id");
  const relationships = parseXml(getText(archive, relationshipEntry(workbookEntry)));
  const relationship = findRelationship(relationships, relationshipId);
  if (!relationship) throw new Error(`找不到工作表关系：${relationshipId}`);
  return {
    name: sheet.getAttribute("name"),
    entry: normalizeZipTarget(workbookEntry, relationship.getAttribute("Target")),
  };
}

function readSharedStrings(archive) {
  const bytes = archive["xl/sharedStrings.xml"];
  if (!bytes) return [];
  const document = parseXml(decoder.decode(bytes));
  return elementsByLocalName(document, "si").map((item) =>
    elementsByLocalName(item, "t").map((text) => text.textContent).join(""),
  );
}

function decodeCellElement(cell, sharedStrings) {
  if (!cell) return "";
  const formula = childByLocalName(cell, "f");
  if (formula) return `=${formula.textContent}`;
  const type = cell.getAttribute("t");
  if (type === "inlineStr") {
    return elementsByLocalName(cell, "t").map((item) => item.textContent).join("");
  }
  const value = childByLocalName(cell, "v")?.textContent ?? "";
  return type === "s" ? sharedStrings[Number(value)] ?? "" : value;
}

function rowNumber(row) {
  return Number(row.getAttribute("r"));
}

function columnFromReference(reference) {
  return reference.match(/^[A-Z]+/)?.[0] ?? "";
}

function columnNumber(column) {
  return [...column].reduce((value, character) => value * 26 + character.charCodeAt(0) - 64, 0);
}

function getRow(sheetDocument, number) {
  return elementsByLocalName(sheetDocument, "row").find((row) => rowNumber(row) === number) ?? null;
}

function getCell(row, reference) {
  if (!row) return null;
  return Array.from(row.childNodes).find(
    (node) => node.nodeType === 1 && node.localName === "c" && node.getAttribute("r") === reference,
  ) ?? null;
}

function readRows(sheetDocument, sharedStrings, columns) {
  const rows = new Map();
  for (const row of elementsByLocalName(sheetDocument, "row")) {
    const number = rowNumber(row);
    const values = {};
    for (const column of columns) {
      values[column] = decodeCellElement(getCell(row, `${column}${number}`), sharedStrings).trim();
    }
    rows.set(number, values);
  }
  return rows;
}

function readDimensionMaxRow(sheetDocument) {
  const dimension = elementsByLocalName(sheetDocument, "dimension")[0]?.getAttribute("ref") ?? "A1";
  return Number(dimension.match(/(\d+)$/)?.[1] ?? 1);
}

function ensureRow(sheetDocument, number) {
  const existing = getRow(sheetDocument, number);
  if (existing) return existing;
  const sheetData = elementsByLocalName(sheetDocument, "sheetData")[0];
  if (!sheetData) throw new Error("目标工作表缺少 sheetData");
  const row = sheetDocument.createElementNS(NS.main, "row");
  row.setAttribute("r", String(number));
  const next = Array.from(sheetData.childNodes).find(
    (node) => node.nodeType === 1 && node.localName === "row" && rowNumber(node) > number,
  );
  sheetData.insertBefore(row, next ?? null);
  return row;
}

function ensureCell(sheetDocument, row, column) {
  const reference = `${column}${rowNumber(row)}`;
  const existing = getCell(row, reference);
  if (existing) return existing;
  const cell = sheetDocument.createElementNS(NS.main, "c");
  cell.setAttribute("r", reference);
  const targetColumn = columnNumber(column);
  const next = Array.from(row.childNodes).find(
    (node) => node.nodeType === 1 && node.localName === "c"
      && columnNumber(columnFromReference(node.getAttribute("r"))) > targetColumn,
  );
  row.insertBefore(cell, next ?? null);
  return cell;
}

function clearChildren(element) {
  while (element.firstChild) element.removeChild(element.firstChild);
}

function writeInlineString(sheetDocument, rowNumberValue, column, value, style) {
  const row = ensureRow(sheetDocument, rowNumberValue);
  const cell = ensureCell(sheetDocument, row, column);
  clearChildren(cell);
  cell.setAttribute("t", "inlineStr");
  cell.setAttribute("s", String(style));
  const inlineString = sheetDocument.createElementNS(NS.main, "is");
  const text = sheetDocument.createElementNS(NS.main, "t");
  text.setAttribute("xml:space", "preserve");
  text.appendChild(sheetDocument.createTextNode(value));
  inlineString.appendChild(text);
  cell.appendChild(inlineString);
}

function prepareImageRow(sheetDocument, rowNumberValue) {
  const row = ensureRow(sheetDocument, rowNumberValue);
  row.setAttribute("ht", "102");
  row.setAttribute("customHeight", "1");
  row.setAttribute("spans", "1:8");
}

function sourceRemarkRecords(sourceArchive) {
  const sourceSheet = resolveSheetEntry(sourceArchive, ["补发表", "Sheet1"]);
  const sheetDocument = parseXml(getText(sourceArchive, sourceSheet.entry));
  const sharedStrings = readSharedStrings(sourceArchive);
  const rows = readRows(sheetDocument, sharedStrings, ["B", "C", "D", "G"]);
  const records = new Map();
  const ensureRecord = (row, values) => {
    if (!values.B || !values.C || !values.D) throw new Error(`源表第 ${row} 行 B/C/D 不完整`);
    if (!records.has(row)) {
      records.set(row, {
        row,
        B: values.B,
        C: values.C,
        D: values.D,
        remarkText: "",
      });
    }
    return records.get(row);
  };

  for (const [row, values] of rows) {
    if (!values.G || /DISPIMG\s*\(/i.test(values.G)) continue;
    ensureRecord(row, values).remarkText = values.G;
  }

  const drawing = elementsByLocalName(sheetDocument, "drawing")[0];
  if (drawing) {
    const drawingRelationshipId = drawing.getAttributeNS(NS.rel, "id") || drawing.getAttribute("r:id");
    const sheetRelationships = parseXml(getText(sourceArchive, relationshipEntry(sourceSheet.entry)));
    const drawingRelationship = findRelationship(sheetRelationships, drawingRelationshipId);
    if (!drawingRelationship) throw new Error("源表绘图关系不存在");
    const drawingEntry = normalizeZipTarget(sourceSheet.entry, drawingRelationship.getAttribute("Target"));
    const drawingDocument = parseXml(getText(sourceArchive, drawingEntry));
    const drawingRelationships = parseXml(getText(sourceArchive, relationshipEntry(drawingEntry)));
    for (const anchor of [
      ...elementsByLocalName(drawingDocument, "oneCellAnchor"),
      ...elementsByLocalName(drawingDocument, "twoCellAnchor"),
    ]) {
      const from = elementsByLocalName(anchor, "from")[0];
      const column = Number(elementsByLocalName(from, "col")[0]?.textContent ?? -1);
      const row = Number(elementsByLocalName(from, "row")[0]?.textContent ?? -1) + 1;
      if (column !== 6) continue;
      const blip = elementsByLocalName(anchor, "blip")[0];
      const imageRelationshipId = blip?.getAttributeNS(NS.rel, "embed") || blip?.getAttribute("r:embed");
      const imageRelationship = findRelationship(drawingRelationships, imageRelationshipId);
      if (!imageRelationship) throw new Error(`源图片关系不存在：${imageRelationshipId}`);
      const mediaEntry = normalizeZipTarget(drawingEntry, imageRelationship.getAttribute("Target"));
      const values = rows.get(row) ?? { B: "", C: "", D: "" };
      Object.assign(ensureRecord(row, values), {
        mediaEntry,
        extension: path.posix.extname(mediaEntry).slice(1).toLowerCase(),
        imageBytes: sourceArchive[mediaEntry],
      });
    }
  }

  if (sourceArchive["xl/cellimages.xml"] && sourceArchive["xl/_rels/cellimages.xml.rels"]) {
    const cellImagesEntry = "xl/cellimages.xml";
    const cellImages = parseXml(getText(sourceArchive, cellImagesEntry));
    const cellImageRelationships = parseXml(getText(sourceArchive, "xl/_rels/cellimages.xml.rels"));
    const imageById = new Map();
    for (const picture of elementsByLocalName(cellImages, "pic")) {
      const id = elementsByLocalName(picture, "cNvPr")[0]?.getAttribute("name");
      const blip = elementsByLocalName(picture, "blip")[0];
      const relationshipId = blip?.getAttributeNS(NS.rel, "embed") || blip?.getAttribute("r:embed");
      const relationship = findRelationship(cellImageRelationships, relationshipId);
      if (!id || !relationship) continue;
      imageById.set(id, normalizeZipTarget(cellImagesEntry, relationship.getAttribute("Target")));
    }
    for (const [row, values] of rows) {
      const imageId = values.G.match(/DISPIMG\(["']([^"']+)/i)?.[1];
      const mediaEntry = imageById.get(imageId);
      if (!mediaEntry || records.get(row)?.imageBytes) continue;
      Object.assign(ensureRecord(row, values), {
        mediaEntry,
        extension: path.posix.extname(mediaEntry).slice(1).toLowerCase(),
        imageBytes: sourceArchive[mediaEntry],
      });
    }
  }
  return [...records.values()].sort((left, right) => left.row - right.row);
}

function sourceImageRecords(sourceArchive) {
  return sourceRemarkRecords(sourceArchive).filter((record) => record.imageBytes);
}

export function readRemarkRecords(sourceBytes) {
  const sourceArchive = unzipSync(new Uint8Array(sourceBytes));
  return sourceRemarkRecords(sourceArchive).map((record) => {
    const output = {
      row: record.row,
      B: record.B,
      C: record.C,
      D: record.D,
      remarkText: record.remarkText,
    };
    if (record.imageBytes) {
      output.extension = record.extension;
      output.imageBytes = new Uint8Array(record.imageBytes);
    }
    return output;
  });
}

export function readImageNoteRecords(sourceBytes) {
  const sourceArchive = unzipSync(new Uint8Array(sourceBytes));
  return sourceImageRecords(sourceArchive).map((record) => ({
    row: record.row,
    B: record.B,
    C: record.C,
    D: record.D,
    extension: record.extension,
    imageBytes: new Uint8Array(record.imageBytes),
  }));
}

function allocateMediaEntry(archive, extension) {
  const numbers = Object.keys(archive)
    .map((entry) => entry.match(/^xl\/media\/image(\d+)\.[^.]+$/)?.[1])
    .filter(Boolean)
    .map(Number);
  return `xl/media/image${nextNumericId(numbers)}.${extension}`;
}

function createRelationshipsDocument() {
  return parseXml(`<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="${NS.pkgRel}"/>`);
}

function relationshipIds(document) {
  return elementsByLocalName(document, "Relationship")
    .map((item) => Number(item.getAttribute("Id").match(/^rId(\d+)$/)?.[1]))
    .filter(Number.isFinite);
}

function appendRelationship(document, { id, type, target }) {
  const relationship = document.createElementNS(NS.pkgRel, "Relationship");
  relationship.setAttribute("Id", id);
  relationship.setAttribute("Type", type);
  relationship.setAttribute("Target", target);
  document.documentElement.appendChild(relationship);
}

function ensureImageContentType(targetArchive, extension) {
  const contentTypesEntry = "[Content_Types].xml";
  const document = parseXml(getText(targetArchive, contentTypesEntry));
  const defaults = elementsByLocalName(document, "Default");
  if (!defaults.some((item) => item.getAttribute("Extension").toLowerCase() === extension)) {
    const item = document.createElementNS(
      "http://schemas.openxmlformats.org/package/2006/content-types",
      "Default",
    );
    item.setAttribute("Extension", extension);
    item.setAttribute("ContentType", extension === "png" ? "image/png" : "image/jpeg");
    document.documentElement.appendChild(item);
    setText(targetArchive, contentTypesEntry, serializeXml(document));
  }
}

function ensureDrawingContentType(targetArchive, drawingEntry) {
  const contentTypesEntry = "[Content_Types].xml";
  const document = parseXml(getText(targetArchive, contentTypesEntry));
  const partName = `/${drawingEntry}`;
  const exists = elementsByLocalName(document, "Override")
    .some((item) => item.getAttribute("PartName") === partName);
  if (exists) return;
  const item = document.createElementNS(
    "http://schemas.openxmlformats.org/package/2006/content-types",
    "Override",
  );
  item.setAttribute("PartName", partName);
  item.setAttribute(
    "ContentType",
    "application/vnd.openxmlformats-officedocument.drawing+xml",
  );
  document.documentElement.appendChild(item);
  setText(targetArchive, contentTypesEntry, serializeXml(document));
}

function imageDimensions(bytes, extension) {
  const data = new Uint8Array(bytes);
  if (extension === "png" && data.length >= 24) {
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    return { width: view.getUint32(16), height: view.getUint32(20) };
  }
  if (["jpg", "jpeg"].includes(extension)) {
    const startOfFrame = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);
    for (let offset = 2; offset + 8 < data.length;) {
      if (data[offset] !== 0xff) {
        offset += 1;
        continue;
      }
      const marker = data[offset + 1];
      if (startOfFrame.has(marker)) {
        return {
          height: (data[offset + 5] << 8) | data[offset + 6],
          width: (data[offset + 7] << 8) | data[offset + 8],
        };
      }
      const length = (data[offset + 2] << 8) | data[offset + 3];
      if (length < 2) break;
      offset += 2 + length;
    }
  }
  return { width: 1, height: 1 };
}

function nextDrawingEntry(archive) {
  const numbers = Object.keys(archive)
    .map((entry) => entry.match(/^xl\/drawings\/drawing(\d+)\.xml$/)?.[1])
    .filter(Boolean)
    .map(Number);
  return `xl/drawings/drawing${nextNumericId(numbers)}.xml`;
}

function appendWorksheetDrawingElement(sheetDocument, relationshipId) {
  const drawing = sheetDocument.createElementNS(NS.main, "drawing");
  drawing.setAttributeNS(NS.rel, "r:id", relationshipId);
  const trailingNames = new Set([
    "legacyDrawing", "legacyDrawingHF", "picture", "oleObjects", "controls",
    "webPublishItems", "tableParts", "extLst",
  ]);
  const next = Array.from(sheetDocument.documentElement.childNodes).find(
    (node) => node.nodeType === 1 && trailingNames.has(node.localName),
  );
  sheetDocument.documentElement.insertBefore(drawing, next ?? null);
}

function createDrawingDocument() {
  return parseXml(`<?xml version="1.0" encoding="UTF-8"?><xdr:wsDr xmlns:xdr="${NS.xdr}" xmlns:a="${NS.a}" xmlns:r="${NS.rel}"/>`);
}

function appendFloatingImage(targetArchive, sheetEntry, sheetDocument, targetRow, imageBytes, extension) {
  const sheetRelationshipsEntry = relationshipEntry(sheetEntry);
  const sheetRelationships = targetArchive[sheetRelationshipsEntry]
    ? parseXml(getText(targetArchive, sheetRelationshipsEntry))
    : createRelationshipsDocument();
  let drawingElement = elementsByLocalName(sheetDocument, "drawing")[0];
  let drawingEntry;
  let drawingDocument;
  let drawingRelationships;
  let drawingRelationshipsEntry;

  if (drawingElement) {
    const drawingRelationshipId = drawingElement.getAttributeNS(NS.rel, "id")
      || drawingElement.getAttribute("r:id");
    const drawingRelationship = findRelationship(sheetRelationships, drawingRelationshipId);
    drawingEntry = normalizeZipTarget(sheetEntry, drawingRelationship.getAttribute("Target"));
    drawingDocument = parseXml(getText(targetArchive, drawingEntry));
    drawingRelationshipsEntry = relationshipEntry(drawingEntry);
    drawingRelationships = parseXml(getText(targetArchive, drawingRelationshipsEntry));
  } else {
    drawingEntry = nextDrawingEntry(targetArchive);
    drawingDocument = createDrawingDocument();
    drawingRelationshipsEntry = relationshipEntry(drawingEntry);
    drawingRelationships = createRelationshipsDocument();
    const drawingRelationshipId = `rId${nextNumericId(relationshipIds(sheetRelationships))}`;
    appendRelationship(sheetRelationships, {
      id: drawingRelationshipId,
      type: `${NS.rel}/drawing`,
      target: path.posix.relative(path.posix.dirname(sheetEntry), drawingEntry),
    });
    appendWorksheetDrawingElement(sheetDocument, drawingRelationshipId);
  }

  const mediaEntry = allocateMediaEntry(targetArchive, extension);
  const imageRelationshipId = `rId${nextNumericId(relationshipIds(drawingRelationships))}`;
  appendRelationship(drawingRelationships, {
    id: imageRelationshipId,
    type: `${NS.rel}/image`,
    target: path.posix.relative(path.posix.dirname(drawingEntry), mediaEntry),
  });

  const dimensions = imageDimensions(imageBytes, extension);
  const widthEmu = 800_000;
  const heightEmu = Math.max(1, Math.round(widthEmu * dimensions.height / dimensions.width));
  const pictureIds = elementsByLocalName(drawingDocument, "cNvPr")
    .map((item) => Number(item.getAttribute("id")))
    .filter(Number.isFinite);
  const pictureId = nextNumericId(pictureIds);
  const anchor = parseXml(`<xdr:oneCellAnchor xmlns:xdr="${NS.xdr}" xmlns:a="${NS.a}" xmlns:r="${NS.rel}">
    <xdr:from><xdr:col>7</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${targetRow - 1}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>
    <xdr:ext cx="${widthEmu}" cy="${heightEmu}"/>
    <xdr:pic>
      <xdr:nvPicPr><xdr:cNvPr id="${pictureId}" name="迁移图片 ${pictureId}" descr="source_image_note"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr>
      <xdr:blipFill><a:blip r:embed="${imageRelationshipId}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>
      <xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${widthEmu}" cy="${heightEmu}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr>
    </xdr:pic>
    <xdr:clientData/>
  </xdr:oneCellAnchor>`).documentElement;
  drawingDocument.documentElement.appendChild(anchor);

  ensureImageContentType(targetArchive, extension);
  ensureDrawingContentType(targetArchive, drawingEntry);
  targetArchive[mediaEntry] = new Uint8Array(imageBytes);
  setText(targetArchive, sheetRelationshipsEntry, serializeXml(sheetRelationships));
  setText(targetArchive, drawingEntry, serializeXml(drawingDocument));
  setText(targetArchive, drawingRelationshipsEntry, serializeXml(drawingRelationships));
  return { drawingEntry, mediaEntry, imageRelationshipId, anchorColumn: 7, anchorRow: targetRow };
}

export function migrateRemarkArchive(sourceBytes, targetBytes) {
  const sourceArchive = unzipSync(new Uint8Array(sourceBytes));
  const targetArchive = unzipSync(new Uint8Array(targetBytes));
  const records = sourceRemarkRecords(sourceArchive);
  if (!records.length) throw new Error("源表没有备注");

  const targetSheet = resolveSheetEntry(targetArchive, ["工作表1", "Sheet1"]);
  const targetSheetDocument = parseXml(getText(targetArchive, targetSheet.entry));
  const targetRows = readRows(targetSheetDocument, readSharedStrings(targetArchive), ["B", "C", "D", "H"]);
  const maxRow = readDimensionMaxRow(targetSheetDocument) + records.length + 1;
  let changed = false;

  for (const sourceRecord of records) {
    const orderNo = String(sourceRecord.B).trim();
    const alreadyExists = Array.from(targetRows.values())
      .some((values) => String(values.B ?? "").trim() === orderNo);
    if (alreadyExists) continue;

    const targetRow = findFirstSafeRow(targetRows, 2, maxRow);
    writeInlineString(targetSheetDocument, targetRow, "B", sourceRecord.B, 4);
    writeInlineString(targetSheetDocument, targetRow, "C", sourceRecord.C, 6);
    writeInlineString(targetSheetDocument, targetRow, "D", sourceRecord.D, 4);
    if (sourceRecord.remarkText) {
      writeInlineString(targetSheetDocument, targetRow, "H", sourceRecord.remarkText, 6);
    }
    if (sourceRecord.imageBytes) {
      prepareImageRow(targetSheetDocument, targetRow);
      appendFloatingImage(
        targetArchive,
        targetSheet.entry,
        targetSheetDocument,
        targetRow,
        sourceRecord.imageBytes,
        sourceRecord.extension,
      );
    }
    targetRows.set(targetRow, {
      B: sourceRecord.B,
      C: sourceRecord.C,
      D: sourceRecord.D,
      H: sourceRecord.imageBytes ? "__IMAGE_NOTE__" : sourceRecord.remarkText,
    });
    changed = true;
  }

  if (!changed) return Buffer.from(targetBytes);
  setText(targetArchive, targetSheet.entry, serializeXml(targetSheetDocument));

  return Buffer.from(zipSync(targetArchive, { level: 6 }));
}

export function migrateImageNoteArchive(sourceBytes, targetBytes) {
  return migrateRemarkArchive(sourceBytes, targetBytes);
}

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

export function verifyMigratedArchive(sourceBytes, targetBytes, outputBytes) {
  const errors = [];
  try {
    const sourceArchive = unzipSync(new Uint8Array(sourceBytes));
    const targetArchive = unzipSync(new Uint8Array(targetBytes));
    const outputArchive = unzipSync(new Uint8Array(outputBytes));
    const sourceRecord = sourceImageRecords(sourceArchive)[0];
    const outputSheet = resolveSheetEntry(outputArchive, ["工作表1", "Sheet1"]);
    const outputDocument = parseXml(getText(outputArchive, outputSheet.entry));
    const outputRows = readRows(outputDocument, readSharedStrings(outputArchive), ["B", "C", "D", "H"]);
    const targetDocument = parseXml(getText(targetArchive, resolveSheetEntry(targetArchive, ["工作表1", "Sheet1"]).entry));
    const targetRow = findFirstSafeRow(
      readRows(targetDocument, readSharedStrings(targetArchive), ["B", "C", "D", "H"]),
      2,
      readDimensionMaxRow(targetDocument) + 1,
    );
    const values = outputRows.get(targetRow);
    for (const column of ["B", "C", "D"]) {
      if (values?.[column] !== sourceRecord[column]) errors.push(`${column}${targetRow} 与源数据不一致`);
    }
    const drawingElement = elementsByLocalName(outputDocument, "drawing")[0];
    if (!drawingElement) errors.push("目标工作表缺少 drawing 关系");
    const sheetRelationships = drawingElement
      ? parseXml(getText(outputArchive, relationshipEntry(outputSheet.entry)))
      : null;
    const drawingRelationshipId = drawingElement
      ? drawingElement.getAttributeNS(NS.rel, "id") || drawingElement.getAttribute("r:id")
      : null;
    const drawingRelationship = sheetRelationships
      ? findRelationship(sheetRelationships, drawingRelationshipId)
      : null;
    if (!drawingRelationship) errors.push("工作表 drawing 关系不存在");
    const drawingEntry = drawingRelationship
      ? normalizeZipTarget(outputSheet.entry, drawingRelationship.getAttribute("Target"))
      : null;
    const drawingDocument = drawingEntry && outputArchive[drawingEntry]
      ? parseXml(getText(outputArchive, drawingEntry))
      : null;
    const matchingAnchor = drawingDocument
      ? [...elementsByLocalName(drawingDocument, "oneCellAnchor"), ...elementsByLocalName(drawingDocument, "twoCellAnchor")]
        .find((anchor) => {
          const from = elementsByLocalName(anchor, "from")[0];
          return Number(elementsByLocalName(from, "col")[0]?.textContent) === 7
            && Number(elementsByLocalName(from, "row")[0]?.textContent) + 1 === targetRow;
        })
      : null;
    if (!matchingAnchor) errors.push(`H${targetRow} 缺少标准图片锚点`);
    const blip = matchingAnchor ? elementsByLocalName(matchingAnchor, "blip")[0] : null;
    const imageRelationshipId = blip?.getAttributeNS(NS.rel, "embed") || blip?.getAttribute("r:embed");
    const drawingRelationships = drawingEntry && outputArchive[relationshipEntry(drawingEntry)]
      ? parseXml(getText(outputArchive, relationshipEntry(drawingEntry)))
      : null;
    const imageRelationship = drawingRelationships
      ? findRelationship(drawingRelationships, imageRelationshipId)
      : null;
    if (!imageRelationship) errors.push("drawing 图片关系不存在");
    const mediaEntry = imageRelationship && drawingEntry
      ? normalizeZipTarget(drawingEntry, imageRelationship.getAttribute("Target"))
      : null;
    if (!mediaEntry || !outputArchive[mediaEntry]) errors.push("迁移后的媒体文件不存在");
    const sourceImageHash = sha256(sourceRecord.imageBytes);
    const outputImageHash = mediaEntry && outputArchive[mediaEntry] ? sha256(outputArchive[mediaEntry]) : "";
    if (sourceImageHash !== outputImageHash) errors.push("迁移图片 SHA-256 与源图片不一致");

    for (const entry of ["xl/cellimages.xml", "xl/_rels/cellimages.xml.rels"]) {
      if (targetArchive[entry] && sha256(targetArchive[entry]) !== sha256(outputArchive[entry])) {
        errors.push(`${entry} 不应被标准图片迁移修改`);
      }
    }

    return {
      ok: errors.length === 0,
      errors,
      sourceRow: sourceRecord.row,
      targetRow,
      sourceImageHash,
      outputImageHash,
      drawingEntry,
      mediaEntry,
      anchorColumn: 7,
      anchorRow: targetRow,
    };
  } catch (error) {
    errors.push(error.message);
    return { ok: false, errors };
  }
}
