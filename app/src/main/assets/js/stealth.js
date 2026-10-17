/* ===========================================================================
 * BandSpark 反检测脚本（stealth）
 * 目的：降低 Android WebView 自动化被抖音风控识别的概率。
 *
 * ⚠️ 说明：
 *   1. 这只能“降低”风险，不能“保证”安全 —— 抖音风控是黑盒。
 *   2. 重点覆盖两类信号：
 *      (a) WebView 浏览器指纹（plugins/mimeTypes/chrome/webdriver/screen 等）
 *      (b) 锁屏/后台可见性（visibilityState/hidden/hasFocus）
 *   3. 页面加载完成后注入，覆盖“自动化执行阶段”的检测；
 *      真正严谨的做法是配合“发送时短暂亮屏”，两者叠加效果最好。
 * =========================================================================== */
(function () {
  'use strict';

  var DESKTOP_UA =
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

  function def(obj, prop, value) {
    try {
      Object.defineProperty(obj, prop, { get: function () { return value; }, configurable: true });
    } catch (e) {}
  }

  /* ---------- 1. UA / 版本 ---------- */
  try { def(Navigator.prototype, 'userAgent', DESKTOP_UA); } catch (e) {}
  try { def(Navigator.prototype, 'appVersion', DESKTOP_UA.replace('Mozilla/', '')); } catch (e) {}
  try { def(Navigator.prototype, 'vendor', 'Google Inc.'); } catch (e) {}
  try { def(Navigator.prototype, 'vendorSub', ''); } catch (e) {}
  try { def(Navigator.prototype, 'productSub', '20030107'); } catch (e) {}

  /* ---------- 2. webdriver / 平台 / 语言 ---------- */
  try { def(Navigator.prototype, 'webdriver', false); } catch (e) {}
  try { def(Navigator.prototype, 'platform', 'Win32'); } catch (e) {}
  try { def(Navigator.prototype, 'languages', ['zh-CN', 'zh', 'en-US', 'en']); } catch (e) {}
  try { def(Navigator.prototype, 'language', 'zh-CN'); } catch (e) {}
  try { def(Navigator.prototype, 'maxTouchPoints', 0); } catch (e) {}
  try { def(Navigator.prototype, 'hardwareConcurrency', 8); } catch (e) {}
  try { def(Navigator.prototype, 'deviceMemory', 8); } catch (e) {}

  /* ---------- 3. window.chrome（无头/WebView 常见缺失） ---------- */
  if (!window.chrome) {
    try {
      window.chrome = {
        app: {},
        csi: function () {},
        loadTimes: function () {},
        runtime: {}
      };
    } catch (e) {}
  }

  /* ---------- 4. plugins / mimeTypes（桌面 Chrome 非空） ---------- */
  function makePlugins() {
    var out = [];
    for (var i = 0; i < 5; i++) {
      out.push({
        name: 'Chrome PDF Plugin',
        filename: 'internal-pdf-viewer',
        description: 'Portable Document Format',
        length: 1,
        0: { type: 'application/pdf', suffixes: 'pdf', description: '' }
      });
    }
    return out;
  }
  try {
    if (!navigator.plugins || navigator.plugins.length === 0) {
      def(Navigator.prototype, 'plugins', makePlugins());
      def(Navigator.prototype, 'mimeTypes', [
        { type: 'application/pdf', suffixes: 'pdf', description: 'Portable Document Format' }
      ]);
    }
  } catch (e) {}

  /* ---------- 5. 屏幕分辨率（桌面尺寸） ---------- */
  try {
    def(window.screen, 'width', 1440);
    def(window.screen, 'height', 900);
    def(window.screen, 'availWidth', 1440);
    def(window.screen, 'availHeight', 900);
    def(window.screen, 'colorDepth', 24);
    def(window.screen, 'pixelDepth', 24);
  } catch (e) {}
  try {
    def(window, 'outerWidth', 1440);
    def(window, 'outerHeight', 900);
    def(window, 'innerWidth', 1440);
    def(window, 'innerHeight', 900);
  } catch (e) {}

  /* ---------- 6. 关键：锁屏/后台可见性伪装 ---------- */
  try {
    Object.defineProperty(document, 'hidden', {
      get: function () { return false; }, configurable: true
    });
    Object.defineProperty(document, 'visibilityState', {
      get: function () { return 'visible'; }, configurable: true
    });
  } catch (e) {}
  try {
    document.hasFocus = function () { return true; };
  } catch (e) {}
})();
