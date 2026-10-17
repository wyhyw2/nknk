/* ===========================================================================
 * BandSpark 抖音网页版 IM 自动化 JS
 * 移植自 DouYinSparkFlow core/douyin_im.py（MIT License，作者 2061360308 及贡献者）
 * 选择器 / React fiber 采集 / 合成输入 / 发送确认 均与 Python 版保持一致
 * =========================================================================== */
(function () {
  'use strict';

  function q(s) { return document.querySelector(s); }
  function qa(s) { return document.querySelectorAll(s); }
  function clean(s) {
    if (s == null) return null;
    return String(s).replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
  }

  window.__spark = {

    /* ---------- 登录态（DOM） ---------- */
    loginDom: function () {
      return {
        loginVisible: !!q('[data-e2e="login-container"]'),
        avatarCard: !!q('[data-e2e="user-avatar-card"]'),
        hasChatRoot: !!q('[data-e2e="msg-input"]')
                  || !!q('.conversationConversationListwrapper'),
        itemCount: qa('[data-e2e="conversation-item"]').length
      };
    },

    /* ---------- 会话列表就绪（≠全量，首屏一页即可） ---------- */
    listReady: function () {
      var items = qa('[data-e2e="conversation-item"]');
      if (items.length === 0) return { ready: false, why: 'no-item', count: 0 };
      var blank = 0;
      for (var i = 0; i < items.length; i++) {
        var t = items[i].querySelector('.conversationConversationItemtitle');
        if (!t || !t.textContent.trim()) blank++;
      }
      if (blank > 0) return { ready: false, why: 'title-blank', blank: blank, count: items.length };
      // 去掉头像加载检查：隐藏 WebView 里图片可能不加载，导致永远卡“就绪中”
      var box = q('.conversationConversationListwrapper');
      if (!box || box.clientHeight <= 0) return { ready: false, why: 'no-container', count: items.length };
      return { ready: true, count: items.length };
    },

    /* ---------- 采集当前虚拟窗口的会话（DOM + React fiber） ---------- */
    collect: function () {
      var ITEM = '[data-e2e="conversation-item"]';
      function convOf(el) {
        var keys = Object.keys(el).filter(function (k) {
          return k.indexOf('__reactProps$') === 0 || k.indexOf('__reactFiber$') === 0;
        });
        for (var i = 0; i < keys.length; i++) {
          var k = keys[i];
          if (k.indexOf('__reactProps$') === 0) {
            var p = el[k];
            if (p && p.conversation && (p.conversation.id || p.conversation.conversationId)) return p.conversation;
            continue;
          }
          var f = el[k], d = 0;
          while (f && d++ < 30) {
            var mp = f.memoizedProps;
            if (mp && mp.conversation && (mp.conversation.id || mp.conversation.conversationId)) return mp.conversation;
            f = f.return;
          }
        }
        return null;
      }
      function txt(el, s) { var n = el.querySelector(s); return n ? clean(n.textContent) : null; }
      function has(el, s) { return !!el.querySelector(s); }
      function safe(fn) { try { return fn(); } catch (e) { return null; } }
      var out = [];
      var nodes = qa(ITEM);
      for (var i = 0; i < nodes.length; i++) {
        (function (el, index) {
          var conv = convOf(el);
          var convId = conv ? (conv.id || conv.conversationId || null) : null;
          var uidRaw = conv ? safe(function () {
            return conv.toParticipantUserId != null ? conv.toParticipantUserId : null;
          }) : null;
          var holder = el.closest('[data-index]');
          out.push({
            index: index,
            dataIndex: holder ? Number(holder.getAttribute('data-index')) : null,
            convId: convId,
            uid: (uidRaw === null || uidRaw === undefined) ? null : String(uidRaw),
            secUid: conv ? (conv.toParticipantSecUserId || null) : null,
            shortId: conv ? (conv.shortId || conv.short_id || null) : null,
            participantCount: conv ? (conv.participantCount || null) : null,
            title: txt(el, '.conversationConversationItemtitle'),
            isCurrent: has(el, '.conversationConversationItemcurConversation'),
            isMuted: has(el, '.ConversationItemTagNextToTitlemuteIcon'),
            fromFiber: !!conv
          });
        })(nodes[i], i);
      }
      return out;
    },

    /* ---------- 滚动状态 ---------- */
    scrollProbe: function () {
      var el = q('.conversationConversationListwrapper');
      if (!el) return { found: false };
      return {
        found: true,
        scrollTop: Math.round(el.scrollTop),
        scrollHeight: el.scrollHeight,
        clientHeight: el.clientHeight,
        atBottom: el.scrollTop + el.clientHeight >= el.scrollHeight - 1,
        notFilled: el.scrollHeight <= el.clientHeight + 1,
        count: qa('[data-e2e="conversation-item"]').length
      };
    },

    scrollTo: function (top) {
      var el = q('.conversationConversationListwrapper');
      if (!el) return false;
      el.scrollTo({ top: top, behavior: 'instant' });
      return true;
    },

    /* ---------- 选中会话（JS 合成点击） ---------- */
    selectIndex: function (i) {
      var el = qa('[data-e2e="conversation-item"]')[i];
      if (!el) return false;
      // 补 Pointer 事件（抖音用 React 的 pointer 处理器），再派发鼠标事件
      try {
        el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
        el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
        el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
        el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
      } catch (e) {}
      // 原生 click 对 React onClick 最可靠
      el.click();
      return true;
    },

    clickSelectIndex: function (i) {
      var el = qa('[data-e2e="conversation-item"]')[i];
      if (!el) return false;
      el.click();
      return true;
    },

    /* ---------- 当前选中会话 ---------- */
    currentConv: function () {
      var el = q('.conversationConversationItemcurConversation');
      if (!el) return null;
      var t = el.querySelector('.conversationConversationItemtitle');
      var title = t ? String(t.textContent).replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim() : null;
      var all = qa('[data-e2e="conversation-item"]');
      var idx = -1;
      for (var i = 0; i < all.length; i++) { if (all[i] === el) { idx = i; break; } }
      var convId = null;
      var keys = Object.keys(el).filter(function (k) { return k.indexOf('__reactFiber$') === 0; });
      for (var i = 0; i < keys.length; i++) {
        var f = el[keys[i]], d = 0;
        while (f && d++ < 30) {
          var p = f.memoizedProps;
          if (p && p.conversation && (p.conversation.id || p.conversation.conversationId)) {
            convId = p.conversation.id || p.conversation.conversationId;
            break;
          }
          f = f.return;
        }
        if (convId) break;
      }
      return { index: idx, title: title, convId: convId };
    },

    /* ---------- 编辑器 ---------- */
    editorEmpty: function () {
      var ed = q('[data-e2e="msg-input"] .public-DraftEditor-content')
            || q('.DraftEditor-root [contenteditable="true"]');
      if (!ed) return null;
      return (ed.textContent || '').trim().length === 0;
    },

    editorClear: function () {
      var ed = q('[data-e2e="msg-input"] .public-DraftEditor-content')
            || q('.DraftEditor-root [contenteditable="true"]');
      if (!ed) return false;
      ed.focus();
      var sel = window.getSelection();
      var r = document.createRange();
      r.selectNodeContents(ed);
      sel.removeAllRanges();
      sel.addRange(r);
      document.execCommand('delete');
      return true;
    },

    /* ---------- 逐行输入（Draft.js 每次 beforeinput 都会重渲染，必须逐行） ---------- */
    typeLine: function (p) {
      var pick = function () {
        return q('[data-e2e="msg-input"] .public-DraftEditor-content')
          || q('.DraftEditor-root [contenteditable="true"]')
          || q('.messageMsgInput [contenteditable="true"]')
          || q('[data-e2e="msg-input"] [contenteditable="true"]')
          || q('[contenteditable="true"]');
      };
      var caretToEnd = function (ed) {
        ed.focus();
        try {
          var r = document.createRange();
          r.selectNodeContents(ed);
          r.collapse(false);
          var s = window.getSelection();
          s.removeAllRanges();
          s.addRange(r);
        } catch (e) {}
        return ed;
      };
      var softBreak = function (ed) {
        for (var i = 0; i < 2; i++) {
          var t = i === 0 ? 'keydown' : 'keyup';
          ed.dispatchEvent(new KeyboardEvent(t, {
            key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
            shiftKey: true, bubbles: true, cancelable: true
          }));
        }
      };
      var ed = pick();
      if (!ed) return { err: 'no-editor' };
      var res = { ins: true, grew: 0, retry: false };
      if (p.text) {
        ed = caretToEnd(ed);
        var before = (ed.textContent || '').length;
        var ok = document.execCommand('insertText', false, p.text);
        var after = (ed.textContent || '').length;
        if (!ok || after <= before) {
          var e2 = caretToEnd(pick() || ed);
          ok = document.execCommand('insertText', false, p.text);
          after = (e2.textContent || '').length;
          res.retry = true;
        }
        res.ins = ok;
        res.grew = after - before;
      }
      if (p.enter) softBreak(pick() || ed);
      res.len = ((pick() || ed).textContent || '').length;
      return res;
    },

    /* ---------- 发送按钮 ---------- */
    clickSend: function () {
      var b = q('.messageMsgInputpublishBtn.messageMsgInputpublishRedBtn')
        || q('[data-e2e="msg-send"]')
        || q('.messageMsgInputpublishBtn');
      if (!b) return null;
      try {
        b.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
        b.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
        b.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window }));
        b.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window }));
      } catch (e) {}
      b.click();
      return 'js';
    },

    /* ---------- 消息列表状态（DOM 回执） ---------- */
    msgState: function () {
      var items = qa('[data-e2e="msg-item-content"]');
      var last = items[items.length - 1];
      return {
        count: items.length,
        lastFromMe: last ? !!last.closest('.MessageBoxContentisFromMe') : null,
        lastText: last ? String(last.textContent).trim().slice(0, 80) : null
      };
    },

    /* ---------- 网络钩子：报告 /v1/message/send 的 HTTP 状态（HTTP 回执） ---------- */
    hookNet: function () {
      if (window.__sparkHook) return 'already';
      window.__sparkHook = true;
      function report(u, st, send) {
        try {
          if (window.Android && window.Android.onNet) {
            window.Android.onNet(JSON.stringify({ url: String(u), status: st, send: send }));
          }
        } catch (e) {}
      }
      var oo = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function (m, u) {
        this.__sparkUrl = String(u);
        this.__sparkSend = this.__sparkUrl.indexOf('/v1/message/send') >= 0;
        return oo.apply(this, arguments);
      };
      var os = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.send = function () {
        var x = this;
        try {
          x.addEventListener('load', function () { report(x.__sparkUrl, x.status, !!x.__sparkSend); });
          x.addEventListener('error', function () { report(x.__sparkUrl, -1, !!x.__sparkSend); });
        } catch (e) {}
        return os.apply(this, arguments);
      };
      if (window.fetch) {
        var of = window.fetch;
        window.fetch = function (u) {
          var send = String(u).indexOf('/v1/message/send') >= 0;
          var p = of.apply(this, arguments);
          try {
            p.then(
              function (r) { report(String(u), r.status, send); },
              function () { report(String(u), -1, send); }
            );
          } catch (e) {}
          return p;
        };
      }
      return 'hooked';
    }
  };
})();
