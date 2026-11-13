// The page behind a link to an app, for a reader whose phone did not open the link in
// ARK-Store. The link is "?id=<name>&p=<package>", as the store writes it. Anyone can write
// such a link, so a name is shown and linked to only when it is made the way the store makes
// names; the rules are the same as in the store's ShareLink.kt. Nothing is fetched.
(function () {
  "use strict";

  var STORE = "org.jarsi.arkstore";
  var RELEASES = "https://github.com/jrs8205/ARK-Store/releases/latest";
  var MAX_NAME = 200;
  var MAX_PARTS = 10;
  var MAX_PACKAGE = 255;
  var PLACE = /^[a-z]{2,20}$/;
  var PART = /^[A-Za-z0-9._-]+$/;
  var PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;

  // Where an app of each place is found on the web, and what the place is called.
  var PLACES = {
    github: { name: "GitHub", page: function (path) { return "https://github.com/" + path; } },
    codeberg: { name: "Codeberg", page: function (path) { return "https://codeberg.org/" + path; } },
    gitlab: { name: "GitLab", page: function (path) { return "https://gitlab.com/" + path; } },
    fdroid: { name: "F-Droid", page: function (path) { return "https://f-droid.org/packages/" + path + "/"; } },
    izzy: { name: "IzzyOnDroid", page: function (path) { return "https://apt.izzysoft.de/fdroid/index/apk/" + path; } }
  };

  // The values given for "id" and "p", or null when either is given twice or escaped wrong.
  function given(query) {
    var values = {};
    var parts = query.replace(/^\?/, "").split("&");
    for (var i = 0; i < parts.length; i++) {
      var at = parts[i].indexOf("=");
      var name = at < 0 ? parts[i] : parts[i].slice(0, at);
      if (name !== "id" && name !== "p") continue;
      if (Object.prototype.hasOwnProperty.call(values, name)) return null;
      try {
        values[name] = decodeURIComponent((at < 0 ? "" : parts[i].slice(at + 1)).replace(/\+/g, " "));
      } catch (e) {
        return null;
      }
    }
    return values;
  }

  // The place and the path a name is made of, or null when it is not a name of an app.
  function named(name) {
    if (typeof name !== "string" || name.length > MAX_NAME) return null;
    var place = "github";
    var path = name;
    var colon = name.indexOf(":");
    if (colon >= 0) {
      place = name.slice(0, colon);
      path = name.slice(colon + 1);
      if (!PLACE.test(place)) return null;
    }
    var parts = path.split("/");
    if (parts.length > MAX_PARTS) return null;
    for (var i = 0; i < parts.length; i++) {
      if (!PART.test(parts[i]) || parts[i] === "." || parts[i] === "..") return null;
    }
    return { place: place, path: path, last: parts[parts.length - 1] };
  }

  var values = given(window.location.search);
  var app = values && named(values.id);
  if (!app) return;
  var pack = typeof values.p === "string" && values.p.length <= MAX_PACKAGE && PACKAGE.test(values.p) ? values.p : null;

  // Only letters that need no escaping are left in the name and the package by now.
  var link = "arkstore.jrs8205.com/app/?id=" + values.id + (pack ? "&p=" + pack : "");
  var known = Object.prototype.hasOwnProperty.call(PLACES, app.place) ? PLACES[app.place] : null;

  document.getElementById("title").textContent = app.last;
  document.getElementById("where").textContent = known ? known.name + " · " + app.path : app.path;
  document.title = app.last + " in ARK-Store";
  document.getElementById("none").hidden = true;
  document.getElementById("named").hidden = false;

  if (known) {
    var source = document.getElementById("source");
    source.href = known.page(app.path);
    source.textContent = "See it on " + known.name;
    source.hidden = false;
  }

  // A phone's browser hands the link to the store when asked this way; without the store it
  // goes to where the store is had from.
  if (/Android/i.test(window.navigator.userAgent)) {
    var open = document.getElementById("open");
    open.href = "intent://" + link + "#Intent;scheme=https;package=" + STORE +
      ";S.browser_fallback_url=" + encodeURIComponent(RELEASES) + ";end";
    open.hidden = false;
    document.getElementById("get").classList.remove("main");
  } else {
    document.getElementById("get").classList.add("main");
  }
})();
