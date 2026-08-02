(function (global) {
  function findWidgetScript() {
    if (document.currentScript) {
      return document.currentScript;
    }

    var scripts = document.getElementsByTagName("script");
    for (var index = scripts.length - 1; index >= 0; index -= 1) {
      var script = scripts[index];
      var src = script.getAttribute("src") || "";
      if (src.indexOf("actbrow-widget.js") !== -1) {
        return script;
      }
    }

    return null;
  }

  function boot() {
    if (!global.Actbrow || !global.Actbrow.createActbrowWidget) {
      throw new Error("actbrow-sdk.js must be loaded before actbrow-widget.js");
    }

    var script = findWidgetScript();
    var config = global.ActbrowWidgetConfig || {};
    var assistantId = config.assistantId || (script && script.getAttribute("data-assistant-id"));
    var baseUrl = config.baseUrl || (script && script.getAttribute("data-base-url"));
    var apiKey = config.apiKey || (script && script.getAttribute("data-api-key"));
    var debug = typeof config.debug === "boolean"
      ? config.debug
      : !!(script && script.getAttribute("data-debug") === "true");
    if (!assistantId) {
      throw new Error("data-assistant-id is required");
    }

    var resolvedBaseUrl = baseUrl || (global.location ? global.location.origin : "");

    var mount = function (theme) {
      global.ActbrowWidget = global.Actbrow.createActbrowWidget({
        assistantId: assistantId,
        baseUrl: resolvedBaseUrl,
        apiKey: apiKey,
        debug: debug,
        navigate: config.navigate,
        router: config.router,
        routerMethod: config.routerMethod,
        labels: config.labels,
        suggestions: config.suggestions,
        theme: theme,
        hideEmptyState: config.hideEmptyState
      });
    };

    // The theme baked into this snippet is only a fallback. Fetching the live one means a branding
    // change in the dashboard takes effect on the next page load, instead of requiring every
    // customer to re-copy and redeploy their embed snippet.
    //
    // The theme is read before mounting rather than applied afterwards: styles and labels are
    // resolved once when the widget is constructed, so re-theming a mounted widget would mean a
    // second styling path and a visible flash. A short timeout keeps a slow or unreachable API from
    // delaying the launcher — in that case the snippet's own theme is used.
    fetchLiveTheme(resolvedBaseUrl, assistantId, apiKey, function (liveTheme) {
      if (!liveTheme) {
        mount(config.theme);
        return;
      }
      // Dashboard wins over the snippet: it is the source of truth an operator just edited.
      var merged = {};
      var key;
      for (key in (config.theme || {})) {
        if (Object.prototype.hasOwnProperty.call(config.theme, key)) merged[key] = config.theme[key];
      }
      for (key in liveTheme) {
        if (Object.prototype.hasOwnProperty.call(liveTheme, key) && liveTheme[key] != null) {
          merged[key] = liveTheme[key];
        }
      }
      mount(merged);
    });
  }

  /** Calls back with the stored theme, or null on any failure — never blocks the widget from mounting. */
  function fetchLiveTheme(baseUrl, assistantId, apiKey, done) {
    if (!baseUrl || typeof fetch !== "function") {
      done(null);
      return;
    }
    var settled = false;
    var finish = function (theme) {
      if (settled) return;
      settled = true;
      done(theme);
    };
    setTimeout(function () { finish(null); }, 2000);

    var headers = {};
    if (apiKey) {
      headers["X-API-Key"] = apiKey;
    }
    fetch(baseUrl.replace(/\/$/, "") + "/v1/assistants/" + encodeURIComponent(assistantId) + "/widget-theme", {
      method: "GET",
      headers: headers
    })
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (body) { finish(body && body.theme ? body.theme : null); })
      .catch(function () { finish(null); });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})(window);
