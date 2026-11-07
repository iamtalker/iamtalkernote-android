// 공유받은 페이지에서 본문을 뽑아 마크다운으로 바꾼다. Readability.js·turndown.js·turndown-plugin-gfm.js 와 사이트 규칙(var __siteRules)을 먼저 넣은 뒤 실행한다.
// 사이트 규칙은 컴퓨터 클리퍼(content.js)의 표를 tools/sync_clipper.py 로 뽑은 것: content(본문 위치)·cleanup(지울 것)·label(제목 글로 바꿀 것)·iframe(본문이 든 틀).
// 결과는 JSON 글자: {"title":…, "markdown":…} 또는 {"error":…}
(function () {
  try {
    var base = location.href;
    var rules = (typeof __siteRules !== "undefined" && __siteRules) || {};
    // 모바일 주소는 PC 짝 호스트의 규칙을 쓴다: 연결 표(alias, MOBILE_HOST_ALIAS)가 먼저, 없으면 m. 을 뗀 주소
    var host = location.hostname;
    var aliases = rules.alias || {};
    var hosts = [host];
    if (aliases[host]) hosts.push(aliases[host]);
    hosts.push(host.replace(/^m\./, "www."), host.replace(/^m\./, ""));
    function rule(kind) {
      var t = rules[kind] || {};
      for (var i = 0; i < hosts.length; i++) if (t[hosts[i]]) return t[hosts[i]];
      return null;
    }
    var srcDoc = document;
    var frameSel = rule("iframe");
    if (frameSel) {
      try { var fr = document.querySelector(frameSel); if (fr && fr.contentDocument && fr.contentDocument.body) { srcDoc = fr.contentDocument; base = fr.contentWindow.location.href; } } catch (e) {}
    }
    function abs(v) { try { return new URL(v, base).href; } catch (e) { return v; } }
    // 제목은 노트 이름으로 이미 저장되므로, 본문 맨 앞 세 줄 안의 같은 제목 줄(헤딩 또는 한 줄)은 뺀다.
    // 글자·숫자만 비교(\p 는 구형 웹뷰에서 문법 오류가 나므로 쓰지 않는다). content.js 의 jcpDropLeadingTitle 과 같은 규칙.
    function key(s) { return String(s || "").replace(/[\s!-\/:-@\[-`{-~ -⁯　-〿！-／：-＠［-｀｛-･]+/g, "").toLowerCase(); }
    function dropLeadingTitle(md, title) {
      var full = key(title);
      var core = key(String(title || "").split(/\s[-|｜–]\s/)[0]);
      if (full.length < 2) return md;
      var lines = md.split("\n"), drop = {}, checked = 0;
      for (var i = 0; i < lines.length && checked < 3; i++) {
        var line = lines[i].trim();
        if (!line) continue;
        checked++;
        var isH = /^#{1,6}\s/.test(line);
        var h = key(isH ? line.replace(/^#{1,6}\s+/, "").replace(/\s+#+\s*$/, "") : line);
        if (!h) continue;
        var same = h === full || h === core ||
          (isH && h.length >= 4 && (core.indexOf(h) >= 0 || (core.length >= 4 && h.indexOf(core) >= 0)));
        if (same) drop[i] = true;
      }
      if (!Object.keys(drop).length) return md;
      return lines.filter(function (_, i) { return !drop[i]; }).join("\n").replace(/^\s*\n/, "");
    }
    var doc = srcDoc.cloneNode(true);
    doc.querySelectorAll("img").forEach(function (im) {
      var lazy = im.getAttribute("data-src") || im.getAttribute("data-original") || im.getAttribute("data-lazy-src");
      var cur = im.getAttribute("src") || "";
      if (lazy && (!cur || cur.indexOf("data:") === 0)) im.setAttribute("src", lazy);
    });
    doc.querySelectorAll("[src]").forEach(function (el) { el.setAttribute("src", abs(el.getAttribute("src"))); });
    doc.querySelectorAll("[href]").forEach(function (el) {
      var h = el.getAttribute("href");
      if (h && h.charAt(0) !== "#" && h.indexOf("javascript:") !== 0) el.setAttribute("href", abs(h));
    });
    var title = (document.title || "").trim();
    var html = null;
    var sel = rule("content");
    if (sel) {
      var els = [];
      try { els = Array.prototype.slice.call(doc.querySelectorAll(sel)); } catch (e) {}
      if (els.length) {
        var box = doc.createElement("div");
        els.forEach(function (el) { var w = doc.createElement("div"); w.appendChild(el); box.appendChild(w); });
        box.querySelectorAll("script,style,noscript,template").forEach(function (el) { el.remove(); });
        var clean = rule("cleanup");
        if (clean && clean.length) { try { box.querySelectorAll(clean.join(",")).forEach(function (el) { el.remove(); }); } catch (e) {} }
        var lab = rule("label");
        if (lab && lab.length) {
          try { box.querySelectorAll(lab.join(",")).forEach(function (el) {
            var t = el.getAttribute("title"); if (t) el.replaceWith(doc.createTextNode(t + " " + el.textContent.trim()));
          }); } catch (e) {}
        }
        html = box.innerHTML;
      }
    }
    if (html === null) {
      var article = new Readability(doc).parse();
      if (!article || !article.content) return JSON.stringify({ error: "nocontent" });
      html = article.content;
      title = (article.title || title).trim();
    }
    var td = new TurndownService({ headingStyle: "atx", codeBlockStyle: "fenced", bulletListMarker: "-" });
    if (typeof turndownPluginGfm !== "undefined") td.use(turndownPluginGfm.gfm);
    td.remove(["script", "style", "noscript", "template"]);
    var md = dropLeadingTitle(td.turndown(html).trim(), title).trim();
    if (!md) return JSON.stringify({ error: "nocontent" });
    return JSON.stringify({ title: title, markdown: md });
  } catch (e) {
    return JSON.stringify({ error: String(e) });
  }
})();
