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
    var debug = typeof config.debug === "boolean"
      ? config.debug
      : !!(script && script.getAttribute("data-debug") === "true");
    if (!assistantId) {
      throw new Error("data-assistant-id is required");
    }

    global.ActbrowWidget = global.Actbrow.createActbrowWidget({
      assistantId: assistantId,
      baseUrl: baseUrl || (global.location ? global.location.origin : ""),
      debug: debug
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})(window);
