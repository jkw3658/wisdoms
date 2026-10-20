/**
 * 格言シート → JSON 配信用 Web アプリ
 *
 * シートは非公開のまま、この Web アプリの URL と TOKEN を知っている人だけが
 * データを取得できます。設定手順は README.md を参照してください。
 */

// ===== 設定 =====
// 推測されにくい長い文字列に変更してください（アプリの設定画面にも同じ値を入力します）
const TOKEN = 'ここを任意の長い文字列に変更';
// 読み込むシート名（空文字の場合は先頭のシート）
const SHEET_NAME = '';
// 1 行目の見出し名。見つからない場合は TEXT は A 列、CATEGORY は B 列を使います
const TEXT_HEADERS = ['本文', '言葉', '格言', 'テキスト', 'text'];
const CATEGORY_HEADERS = ['カテゴリ', 'カテゴリー', '分類', 'category'];
const SOURCE_HEADERS = ['出典', '作者', '人物', 'source', 'author'];
// ================

function doGet(e) {
  if (!e || !e.parameter || e.parameter.token !== TOKEN) {
    return json_({ error: 'unauthorized' });
  }

  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = SHEET_NAME ? ss.getSheetByName(SHEET_NAME) : ss.getSheets()[0];
  if (!sheet) {
    return json_({ error: 'sheet not found: ' + SHEET_NAME });
  }

  const values = sheet.getDataRange().getDisplayValues();
  if (values.length < 2) {
    return json_({ items: [] });
  }

  const header = values[0].map(function (h) { return String(h).trim().toLowerCase(); });
  const find = function (names, fallback) {
    for (let i = 0; i < names.length; i++) {
      const idx = header.indexOf(names[i].toLowerCase());
      if (idx >= 0) return idx;
    }
    return fallback;
  };
  const textCol = find(TEXT_HEADERS, 0);
  const categoryCol = find(CATEGORY_HEADERS, header.length > 1 ? 1 : -1);
  const sourceCol = find(SOURCE_HEADERS, -1);

  const items = [];
  for (let r = 1; r < values.length; r++) {
    const row = values[r];
    const text = String(row[textCol] || '').trim();
    if (!text) continue;
    items.push({
      text: text,
      category: categoryCol >= 0 ? String(row[categoryCol] || '').trim() : '',
      source: sourceCol >= 0 ? String(row[sourceCol] || '').trim() : '',
    });
  }
  return json_({ items: items });
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
