/**
 * 格言シート → JSON 配信用 Web アプリ
 *
 * シートは非公開のまま、この Web アプリの URL と TOKEN を知っている人だけが
 * データを取得・チェックの書き込みをできます。設定手順は README.md を参照してください。
 */

// ===== 設定 =====
// 推測されにくい長い文字列に変更してください（アプリの設定画面にも同じ値を入力します）
const TOKEN = 'ここを任意の長い文字列に変更';
// 読み込むシート名（空文字の場合は先頭のシート）
const SHEET_NAME = '';
// 1 行目の見出し名。見つからない場合は TEXT は A 列、CATEGORY は B 列を使います
const TEXT_HEADERS = ['本文', '言葉', 'ことば', '格言', 'テキスト', 'text'];
const CATEGORY_HEADERS = ['カテゴリ', 'カテゴリー', '分類', 'category'];
const SOURCE_HEADERS = ['出典', '作者', '人物', 'source', 'author'];
// アプリでチェックを付けたときに書き込む列（見つからない場合はチェック機能は使えません）
const CHECK_HEADERS = ['チェック', 'check'];
// ================

function doGet(e) {
  const p = (e && e.parameter) || {};
  if (p.token !== TOKEN) {
    return json_({ error: 'unauthorized' });
  }
  const ctx = open_();
  if (ctx.error) return json_(ctx);
  if (p.action === 'check') return json_(check_(ctx, p));
  return json_(list_(ctx));
}

function open_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = SHEET_NAME ? ss.getSheetByName(SHEET_NAME) : ss.getSheets()[0];
  if (!sheet) {
    return { error: 'sheet not found: ' + SHEET_NAME };
  }
  const range = sheet.getDataRange();
  const display = range.getDisplayValues();
  const raw = range.getValues();
  const header = (display[0] || []).map(function (h) { return String(h).trim().toLowerCase(); });
  const find = function (names, fallback) {
    for (let i = 0; i < names.length; i++) {
      const idx = header.indexOf(names[i].toLowerCase());
      if (idx >= 0) return idx;
    }
    return fallback;
  };
  return {
    sheet: sheet,
    display: display,
    raw: raw,
    textCol: find(TEXT_HEADERS, 0),
    categoryCol: find(CATEGORY_HEADERS, header.length > 1 ? 1 : -1),
    sourceCol: find(SOURCE_HEADERS, -1),
    checkCol: find(CHECK_HEADERS, -1),
  };
}

function list_(ctx) {
  const items = [];
  for (let r = 1; r < ctx.display.length; r++) {
    const row = ctx.display[r];
    const text = String(row[ctx.textCol] || '').trim();
    if (!text) continue;
    items.push({
      row: r + 1, // シート上の行番号（1 始まり）
      text: text,
      category: ctx.categoryCol >= 0 ? String(row[ctx.categoryCol] || '').trim() : '',
      source: ctx.sourceCol >= 0 ? String(row[ctx.sourceCol] || '').trim() : '',
      checked: ctx.checkCol >= 0 ? isChecked_(ctx.raw[r][ctx.checkCol]) : false,
    });
  }
  return { items: items, canCheck: ctx.checkCol >= 0 };
}

function check_(ctx, p) {
  if (ctx.checkCol < 0) {
    return { error: '「チェック」列が見つかりません' };
  }
  const head = String(p.head || '');
  const textAt = function (row) {
    const r = ctx.display[row - 1];
    return r ? String(r[ctx.textCol] || '').trim() : '';
  };
  // 取得後に行が並べ替えられていても、本文の先頭で正しい行を探す
  let row = parseInt(p.row, 10);
  if (!(row >= 2 && textAt(row).indexOf(head) === 0)) {
    row = -1;
    for (let r = 2; r <= ctx.display.length; r++) {
      if (textAt(r) && textAt(r).indexOf(head) === 0) { row = r; break; }
    }
  }
  if (row < 0) {
    return { error: 'シート上に該当する行が見つかりません' };
  }
  ctx.sheet.getRange(row, ctx.checkCol + 1).setValue(p.checked === 'true');
  return { ok: true, row: row };
}

function isChecked_(v) {
  if (v === true) return true;
  return /^(true|✓|✔|○|〇|済)$/i.test(String(v).trim());
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
