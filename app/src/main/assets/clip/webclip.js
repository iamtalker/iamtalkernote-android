// 공유받은 페이지에서 본문을 뽑아 마크다운으로 바꾼다. Readability.js·turndown.js·turndown-plugin-gfm.js 와 사이트 규칙(var __siteRules)을 먼저 넣은 뒤 실행한다.
// 사이트 규칙은 컴퓨터 클리퍼(content.js)의 표를 tools/sync_clipper.py 로 뽑은 것: content(본문 위치)·cleanup(지울 것)·label(제목 글로 바꿀 것)·iframe(본문이 든 틀).
// 결과는 JSON 글자: {"title":…, "markdown":…} 또는 {"error":…}
(function () {
  try {
    var base = location.href;
    var rules = (typeof __siteRules !== "undefined" && __siteRules) || {};
    // 모바일 주소(m.example.com)는 같은 사이트의 www 규칙을 쓴다
    var host = location.hostname;
    var hosts = [host, host.replace(/^m\./, "www."), host.replace(/^m\./, "")];
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
    var md = td.turndown(html).trim();
    if (!md) return JSON.stringify({ error: "nocontent" });
    return JSON.stringify({ title: title, markdown: md });
  } catch (e) {
    return JSON.stringify({ error: String(e) });
  }
})();
