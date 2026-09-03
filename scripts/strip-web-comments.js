// Build 前置步驟：清走 assets/web 入面 JS/HTML/CSS 嘅 comment，
// 淨係喺 CI build output 度生效，唔會改到 repo 入面嘅原始碼。
// 用 terser 處理 .js (compress/mangle 全關，淨係去 comment)，
// HTML/CSS 用簡單 regex 去 <!-- --> 同 /* */。
// 刻意跳過 .json (JSON 冇 comment 語法，唔可以亂咁刪 // 或 /* */，
// 唔小心會累到合法字串內容)，同埋第三方壓縮庫 (blockly_compressed.js
// 等 *_compressed.js / blockly.css) 因為已經冇乜 comment 可去，跳過慳返
// build time。
"use strict";

const fs = require("fs");
const path = require("path");
const { minify } = require("terser");

const WEB_DIR = path.join(__dirname, "..", "app", "src", "main", "assets", "web");
const SKIP_FILES = new Set([
  "blockly_compressed.js",
  "blocks_compressed.js",
]);

async function stripJs(filePath) {
  const src = fs.readFileSync(filePath, "utf8");
  const result = await minify(src, {
    compress: false,
    mangle: false,
    format: { comments: false, beautify: true, indent_level: 2 },
  });
  if (result.error) {
    throw result.error;
  }
  fs.writeFileSync(filePath, result.code, "utf8");
}

function stripHtml(filePath) {
  const src = fs.readFileSync(filePath, "utf8");
  const out = src.replace(/<!--[\s\S]*?-->/g, "");
  fs.writeFileSync(filePath, out, "utf8");
}

function stripCss(filePath) {
  const src = fs.readFileSync(filePath, "utf8");
  const out = src.replace(/\/\*[\s\S]*?\*\//g, "");
  fs.writeFileSync(filePath, out, "utf8");
}

async function walk(dir) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      await walk(full);
      continue;
    }
    if (SKIP_FILES.has(entry.name)) {
      console.log("skip:", full);
      continue;
    }
    if (entry.name.endsWith(".js")) {
      console.log("strip js:", full);
      await stripJs(full);
    } else if (entry.name.endsWith(".html")) {
      console.log("strip html:", full);
      stripHtml(full);
    } else if (entry.name.endsWith(".css")) {
      console.log("strip css:", full);
      stripCss(full);
    }
  }
}

walk(WEB_DIR).then(() => {
  console.log("done");
}).catch((err) => {
  console.error("strip-web-comments failed:", err);
  process.exit(1);
});
