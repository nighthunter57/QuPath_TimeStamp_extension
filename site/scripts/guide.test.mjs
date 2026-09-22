import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import * as React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const site = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = readFileSync(resolve(site, "data/install-guide.ts"), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
const exports = {};
new Function("exports", compiled)(exports);
const { computers, doctorPackage, recorderSteps, helpItems } = exports;
const page = readFileSync(resolve(site, "app/page.tsx"), "utf8");
const css = readFileSync(resolve(site, "app/globals.css"), "utf8");
const zip = resolve(site, "public", doctorPackage.href);
const require = createRequire(import.meta.url);

// Render each selected computer without starting a browser or opening a microphone.
for (const computer of ["windows", "mac", "linux"]) {
  test(`renders the ${computer} guide and its matching download`, () => {
    const code = ts.transpileModule(page, { compilerOptions: {
      module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true,
    } }).outputText;
    const component = {};
    const dependencies = name => {
      if (name === "react") return { ...React, useState: () => [computer, () => {}] };
      if (name === "next/image") return { __esModule: true, default: () => null };
      // Content tests omit decorative icons; the Next build resolves the actual icon bundle.
      if (name === "lucide-react") return new Proxy({}, { get: () => () => null });
      if (name === "@/assets/histology-support-banner.jpg") return { __esModule: true, default: "test-image.jpg" };
      if (name === "@/data/install-guide") return exports;
      return require(name);
    };
    new Function("exports", "require", code)(component, dependencies);
    const html = renderToStaticMarkup(React.createElement(component.default));
    assert.ok(html.includes(computers[computer].installer));
    assert.ok(html.includes(`Download TimeStamp for ${computers[computer].name}`));
    assert.ok(html.includes(`Installation guide for ${computers[computer].name}`));
    assert.equal((html.match(/<h1[ >]/g) ?? []).length, 1);
    assert.equal(html.includes("terminal-command"), computer === "linux");
  });
}

test("all three computer guides have instructions and exact installer filenames", () => {
  assert.deepEqual(Object.keys(computers), ["windows", "mac", "linux"]);
  for (const guide of Object.values(computers)) {
    for (const field of ["name", "detail", "qupath", "extract", "installer", "run", "help"]) {
      assert.ok(guide[field].length > 2, field);
    }
  }
  const files = execFileSync("unzip", ["-Z1", zip], { encoding: "utf8" }).split("\n");
  for (const guide of Object.values(computers)) {
    assert.ok(files.includes(`TimeStamp-Doctor-${doctorPackage.version}/${guide.installer}`), guide.installer);
  }
});

test("linked package is present, complete, and contains the current pause protocol", () => {
  assert.ok(existsSync(zip));
  assert.ok(existsSync(resolve(site, "public", doctorPackage.instructions)));
  execFileSync("unzip", ["-tq", zip]);
  const prefix = `TimeStamp-Doctor-${doctorPackage.version}/`;
  const checksums = execFileSync("unzip", ["-p", zip, `${prefix}CHECKSUMS-SHA256.txt`], { encoding: "utf8" });
  for (const line of checksums.trim().split("\n")) {
    const [, expected, name] = line.match(/^([a-f0-9]{64})\s+(.+)$/);
    const bytes = execFileSync("unzip", ["-p", zip, prefix + name], { maxBuffer: 4 * 1024 * 1024 });
    assert.equal(createHash("sha256").update(bytes).digest("hex"), expected, name);
  }
  const helper = execFileSync("unzip", ["-p", zip, `${prefix}live_whisper_demo.py`], { encoding: "utf8", maxBuffer: 4 * 1024 * 1024 });
  assert.match(helper, /CAPTURE_STATE/);
  assert.match(helper, /interactive-control/);
});

test("tutorial follows the real recorder controls and avoids old promises", () => {
  assert.equal(recorderSteps.length, 6);
  const copy = JSON.stringify(recorderSteps);
  for (const label of ["Open Clinical Session Recorder", "Test microphone", "Start Recording", "Pause", "Resume", "Done", "Save Session"]) assert.ok(copy.includes(label), label);
  assert.doesNotMatch(page, /Select Session Folder|Open live event monitor|About 5 minutes|source of truth/);
  assert.ok(helpItems.some(item => item.answer.includes("not a clinically validated")));
  assert.ok(helpItems.some(item => item.answer.includes("does not erase")));
});

test("guide includes keyboard-native choices, skip navigation, print and reduced-motion styles", () => {
  assert.match(page, /type="radio"/);
  assert.match(page, /<fieldset/);
  assert.match(page, /className="skip-link"/);
  assert.match(page, /<details/);
  assert.match(css, /@media print/);
  assert.match(css, /prefers-reduced-motion/);
  assert.match(css, /max-width: 380px/);
  const ids = new Set([...page.matchAll(/id="([^"]+)"/g)].map(match => match[1]));
  for (const match of page.matchAll(/href="#([^"]+)"/g)) assert.ok(ids.has(match[1]), `Missing anchor ${match[1]}`);
});

test("GitHub Pages export has working local assets and the current download", { skip: !existsSync(resolve(site, "out/index.html")) }, () => {
  const html = readFileSync(resolve(site, "out/index.html"), "utf8");
  assert.match(html, /Your observations/);
  assert.ok(html.includes(doctorPackage.href));
  assert.match(html, /https:\/\/nighthunter57.github.io\/QuPath_TimeStamp_extension\/og.png/);
  for (const match of html.matchAll(/(?:src|href)="([^"#]+)"/g)) {
    const url = match[1].split("?")[0];
    if (url.startsWith("/_next/") || url.startsWith("/QuPath_TimeStamp_extension/")) {
      const file = url.replace(/^\/QuPath_TimeStamp_extension\//, "").replace(/^\//, "");
      if (file) assert.ok(existsSync(resolve(site, "out", file)), `Missing exported asset ${file}`);
    }
  }
});
