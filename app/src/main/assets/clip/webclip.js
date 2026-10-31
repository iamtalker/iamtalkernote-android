// 공유받은 페이지에서 본문을 뽑아 마크다운으로 바꾼다. Readability.js·turndown.js·turndown-plugin-gfm.js 를 먼저 넣은 뒤 실행한다.
// 결과는 JSON 글자: {"title":…, "markdown":…} 또는 {"error":…}
(function () {
  try {
    var base = location.href;
    var doc = document.cloneNode(true);
    function abs(v) { try { return new URL(v, base).href; } catch (e) { return v; } }
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
    var article = new Readability(doc).parse();
    if (!article || !article.content) return JSON.stringify({ error: "nocontent" });
    var td = new TurndownService({ headingStyle: "atx", codeBlockStyle: "fenced", bulletListMarker: "-" });
    if (typeof turndownPluginGfm !== "undefined") td.use(turndownPluginGfm.gfm);
    td.remove(["script", "style", "noscript", "template"]);
    var md = td.turndown(article.content).trim();
    if (!md) return JSON.stringify({ error: "nocontent" });
    return JSON.stringify({ title: (article.title || document.title || "").trim(), markdown: md });
  } catch (e) {
    return JSON.stringify({ error: String(e) });
  }
})();
