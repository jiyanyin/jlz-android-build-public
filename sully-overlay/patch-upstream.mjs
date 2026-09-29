import fs from 'node:fs';
import path from 'node:path';

const root = process.argv[2];
if (!root || !fs.existsSync(path.join(root, 'index.html'))) {
  throw new Error('Usage: node patch-upstream.mjs <pinned-sully-source-root>');
}
function edit(file, fn) {
  const target = path.join(root, file);
  const before = fs.readFileSync(target, 'utf8');
  const after = fn(before);
  if (after === before) throw new Error('Patch anchor not found: ' + file);
  fs.writeFileSync(target, after);
}
function replaceOnce(text, pattern, replacement, name) {
  const globalPattern = new RegExp(pattern.source, pattern.flags.includes('g') ? pattern.flags : pattern.flags + 'g');
  const matches = [...text.matchAll(globalPattern)];
  if (matches.length !== 1) throw new Error(name + ': expected one anchor, found ' + matches.length);
  return text.replace(pattern, replacement);
}
// Keep the source's desktop/page components, but rename the app tiles
// whose meaning is a pre-existing JLZ native route.
edit('constants.tsx', (before) => {
  const tiles = [
    "export const INSTALLED_APPS: AppConfig[] = [",
    "  { id: AppID.Chat, name: 'ChatGPT', icon: 'Chat', color: 'blue' },",
    "  { id: AppID.Room, name: '状态灯', icon: 'Room', color: 'rose' },",
    "  { id: AppID.Journal, name: '时间链', icon: 'Journal', color: 'pink' },",
    "  { id: AppID.Social, name: '手机轨迹', icon: 'Social', color: 'blue' },",
    "  { id: AppID.Schedule, name: '你我之间', icon: 'Schedule', color: 'pink' },",
    "  { id: AppID.Study, name: '伴读', icon: 'Study', color: 'blue' },",
    "  { id: AppID.CheckPhone, name: '设备状态', icon: 'CheckPhone', color: 'slate' },",
    "  { id: AppID.Appearance, name: '外观', icon: 'Appearance', color: 'slate' },",
    "  { id: AppID.Settings, name: '权限检查', icon: 'Settings', color: 'slate' },",
    "];",
  ].join('\n');
  let text = replaceOnce(before,
    /export const INSTALLED_APPS: AppConfig\[\] = \[[\s\S]*?\n\];/,
    tiles, 'INSTALLED_APPS');
  text = replaceOnce(text, /export const DOCK_APPS = \[[^\]]+\];/,
    'export const DOCK_APPS = [AppID.Chat, AppID.Study, AppID.Room, AppID.Settings];', 'DOCK_APPS');
  return text;
});
edit('components/os/AppIcon.tsx', (before) => {
  let text = before.replace("import React from 'react';",
    "import React from 'react';\nimport { tryNativeApp } from '../../jlz-overlay/nativeAppBridge';");
  if (text === before) throw new Error('AppIcon import not found');
  const occurrences = [...text.matchAll(/onClick=\{onClick\}/g)].length;
  if (occurrences !== 2) throw new Error('AppIcon click anchors changed: ' + occurrences);
  text = text.replaceAll('onClick={onClick}',
    'onClick={() => { if (!tryNativeApp(app.id)) onClick(); }}');
  return text;
});
edit('index.html', (before) => {
  let text = replaceOnce(before, /<script src="https:\/\/cdn\.tailwindcss\.com"><\/script>/,
    '<script>window.tailwind={};</script>', 'tailwind CDN');
  text = text.replace(/<link href="https:\/\/fonts\.googleapis\.com[^>]+>/g, '');
  text = text.replace(/<link rel="stylesheet" href="https:\/\/unpkg\.com\/katex[^>]+>/g, '');
  text = text.replace('<meta name="theme-color" content="#0f1115" />',
    '<meta name="theme-color" content="#fff2fa" />');
  text = text.replace('<title>SullyOS·糯米机</title>',
    '<title>世界之间 · 糯米机试验</title>');
  text = replaceOnce(text, /<\/head>/,
    '  <link rel="stylesheet" href="./jlz-tailwind.css" />\n  <link rel="stylesheet" href="./jlz-glass.css" />\n</head>', 'head');
  return text;
});
console.log('Pinned source patched: launcher tiles / icon clicks / offline Tailwind / pastel glass');
