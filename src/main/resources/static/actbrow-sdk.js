(function (global) {
  function debugEnabled(config) {
    return !!(config && config.debug);
  }

  function debugLog(config) {
    if (!debugEnabled(config)) {
      return;
    }
    var args = Array.prototype.slice.call(arguments, 1);
    args.unshift("[Actbrow]");
    console.log.apply(console, args);
  }

  function injectStyles(cssText, id) {
    if (document.getElementById(id)) {
      return;
    }
    var style = document.createElement("style");
    style.id = id;
    style.textContent = cssText;
    document.head.appendChild(style);
  }

  function pageTrackerState() {
    if (typeof window === "undefined") {
      return { href: "", epoch: 1 };
    }
    if (!window.__actbrowPageTracker) {
      window.__actbrowPageTracker = {
        href: window.location ? window.location.href : "",
        epoch: 1
      };
    }
    var tracker = window.__actbrowPageTracker;
    var href = window.location ? window.location.href : "";
    var changed = tracker.href !== href;
    if (changed) {
      tracker.epoch += 1;
      tracker.href = href;
    }
    return {
      href: href,
      epoch: tracker.epoch,
      changed: changed
    };
  }

  function createEmitter() {
    var handlers = {};
    return {
      on: function (eventName, handler) {
        handlers[eventName] = handlers[eventName] || [];
        handlers[eventName].push(handler);
      },
      emit: function (eventName, payload) {
        (handlers[eventName] || []).forEach(function (handler) {
          handler(payload);
        });
      }
    };
  }

  function softToolFailure(toolName, args, message, extras) {
    var payload = {
      tool: toolName,
      arguments: args || {},
      message: message
    };
    if (extras) {
      Object.keys(extras).forEach(function (key) {
        payload[key] = extras[key];
      });
    }
    return {
      success: false,
      structuredOutput: JSON.stringify(payload),
      textSummary: message,
      error: message
    };
  }

  /**
   * Resolve once the DOM has been quiet (no mutations) for `idleMs`. Caps total wait at
   * `maxMs` so a continuously animating page can't hang the run. Used before
   * page.screenshot so React route changes finish hydrating before we read innerText.
   */
  function waitForDomStable(idleMs, maxMs) {
    var idleTarget = typeof idleMs === "number" ? idleMs : 500;
    var deadline = Date.now() + (typeof maxMs === "number" ? maxMs : 4000);
    if (typeof MutationObserver === "undefined" || !document.body) {
      return new Promise(function (resolve) { setTimeout(resolve, idleTarget); });
    }
    return new Promise(function (resolve) {
      var lastMutation = Date.now();
      var observer = new MutationObserver(function () { lastMutation = Date.now(); });
      observer.observe(document.body, {
        childList: true,
        subtree: true,
        characterData: true,
        attributes: true
      });
      function tick() {
        var now = Date.now();
        if (now - lastMutation >= idleTarget || now >= deadline) {
          observer.disconnect();
          resolve();
          return;
        }
        setTimeout(tick, 50);
      }
      setTimeout(tick, idleTarget);
    });
  }

  /**
   * Lazily load html2canvas from a CDN. Resolves once `window.html2canvas` is available.
   * Only fetched when snapshot mode is `image`; text mode never pays this cost.
   */
  var html2canvasLoadPromise = null;
  var unsupportedCanvasColorRe = /\b(?:oklab|oklch|lab|lch|color|color-mix)\(/i;
  var html2CanvasColorProperties = [
    "color",
    "backgroundColor",
    "borderTopColor",
    "borderRightColor",
    "borderBottomColor",
    "borderLeftColor",
    "outlineColor",
    "textDecorationColor",
    "fill",
    "stroke"
  ];
  function loadHtml2Canvas() {
    if (typeof window === "undefined") {
      return Promise.reject(new Error("html2canvas requires a browser environment"));
    }
    if (window.html2canvas) {
      return Promise.resolve(window.html2canvas);
    }
    if (html2canvasLoadPromise) {
      return html2canvasLoadPromise;
    }
    html2canvasLoadPromise = new Promise(function (resolve, reject) {
      var s = document.createElement("script");
      s.src = "https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js";
      s.async = true;
      s.onload = function () {
        if (window.html2canvas) resolve(window.html2canvas);
        else reject(new Error("html2canvas loaded but not on window"));
      };
      s.onerror = function () {
        html2canvasLoadPromise = null;
        reject(new Error("Failed to load html2canvas from CDN"));
      };
      document.head.appendChild(s);
    });
    return html2canvasLoadPromise;
  }

  function hasUnsupportedCanvasColor(value) {
    return !!value && unsupportedCanvasColorRe.test(value);
  }

  function sanitizeHtml2CanvasClone(clonedDocument) {
    var view = clonedDocument.defaultView;
    if (!view || !clonedDocument.body) return;
    var nodes = [clonedDocument.documentElement, clonedDocument.body].concat(
      Array.prototype.slice.call(clonedDocument.body.querySelectorAll("*"))
    );

    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!el || !el.style) continue;
      var computed;
      try {
        computed = view.getComputedStyle(el);
      } catch (e) {
        continue;
      }

      for (var j = 0; j < html2CanvasColorProperties.length; j++) {
        var prop = html2CanvasColorProperties[j];
        var value = computed[prop];
        if (!hasUnsupportedCanvasColor(value)) continue;
        if (prop === "color") {
          el.style.setProperty(prop, "rgb(17, 24, 39)", "important");
        } else if (prop === "backgroundColor") {
          el.style.setProperty(prop, "rgba(255, 255, 255, 0)", "important");
        } else {
          el.style.setProperty(prop, "rgba(0, 0, 0, 0)", "important");
        }
      }

      if (hasUnsupportedCanvasColor(computed.boxShadow)) {
        el.style.setProperty("box-shadow", "none", "important");
      }
      if (hasUnsupportedCanvasColor(computed.textShadow)) {
        el.style.setProperty("text-shadow", "none", "important");
      }
    }
  }

  function textSnapshotResult(loc, imageError) {
    var rawText = (document.body && document.body.innerText) || "";
    var visibleText = rawText.replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
    var labels = collectSemanticLabels(200);
    if (labels.length > 0) {
      visibleText = visibleText + "\n\n--- Labels (alt / aria-label / placeholder) ---\n" + labels.join("\n");
    }
    var maxChars = 12000;
    var truncated = false;
    if (visibleText.length > maxChars) {
      visibleText = visibleText.slice(0, maxChars);
      truncated = true;
    }
    return {
      success: true,
      structuredOutput: JSON.stringify({
        mode: "text",
        path: loc.pathname || "",
        url: loc.href || "",
        title: document.title || "",
        visibleText: visibleText,
        labelCount: labels.length,
        truncated: truncated,
        imageError: imageError || null
      }),
      textSummary: imageError
        ? "Image capture failed; returned text snapshot of " + (loc.pathname || "/") + " (" + visibleText.length + " chars, " + labels.length + " labels" + (truncated ? ", truncated" : "") + ")"
        : "Page snapshot of " + (loc.pathname || "/") + " (" + visibleText.length + " chars, " + labels.length + " labels" + (truncated ? ", truncated" : "") + ")"
    };
  }

  var forbiddenBrowserHttpHeaders = {
    "accept-charset": true,
    "accept-encoding": true,
    "access-control-request-headers": true,
    "access-control-request-method": true,
    "connection": true,
    "content-length": true,
    "cookie": true,
    "cookie2": true,
    "date": true,
    "dnt": true,
    "expect": true,
    "host": true,
    "keep-alive": true,
    "origin": true,
    "referer": true,
    "te": true,
    "trailer": true,
    "transfer-encoding": true,
    "upgrade": true,
    "via": true
  };

  function isObjectRecord(value) {
    return !!value && typeof value === "object" && !Array.isArray(value);
  }

  function joinBrowserHttpUrl(baseUrl, path) {
    if (!baseUrl) return path || "/";
    if (!path || path === "/") return baseUrl;
    return baseUrl.replace(/\/+$/, "") + "/" + path.replace(/^\/+/, "");
  }

  /**
   * Walk the DOM and collect semantic-only labels (alt, aria-label, title, placeholder)
   * from elements whose visible text is missing or shorter than the label. Used to surface
   * names that live in image alt attributes, icon buttons with aria-label, etc., which
   * `body.innerText` does not include.
   */
  function collectSemanticLabels(maxItems) {
    var cap = typeof maxItems === "number" ? maxItems : 200;
    var lines = [];
    if (!document.body || !document.body.querySelectorAll) {
      return lines;
    }
    var nodes = document.body.querySelectorAll("img[alt], [aria-label], [title], input[placeholder], textarea[placeholder]");
    var seen = Object.create(null);
    for (var i = 0; i < nodes.length && lines.length < cap; i++) {
      var el = nodes[i];
      var visible = (el.innerText || el.textContent || "").trim();
      var alt = el.tagName === "IMG" ? (el.getAttribute("alt") || "").trim() : "";
      var aria = (el.getAttribute && el.getAttribute("aria-label") || "").trim();
      var title = (el.getAttribute && el.getAttribute("title") || "").trim();
      var ph = (el.tagName === "INPUT" || el.tagName === "TEXTAREA") ? (el.getAttribute("placeholder") || "").trim() : "";
      var label = aria || alt || title || ph;
      if (!label) continue;
      // Skip when the visible text already covers the label.
      if (visible && visible.toLowerCase().indexOf(label.toLowerCase()) !== -1) continue;
      var kind = alt ? "image" : ph ? "input placeholder" : el.tagName === "BUTTON" || el.getAttribute("role") === "button" ? "button" : el.tagName === "A" ? "link" : "label";
      var line = "[" + kind + ': "' + label + '"]';
      if (seen[line]) continue;
      seen[line] = true;
      lines.push(line);
    }
    return lines;
  }

  function isProbablyVisible(el) {
    if (!el || typeof el.getBoundingClientRect !== "function") {
      return true;
    }
    var rect = el.getBoundingClientRect();
    if (rect.width < 1 || rect.height < 1) {
      return false;
    }
    try {
      var st = window.getComputedStyle(el);
      if (st.display === "none" || st.visibility === "hidden" || Number(st.opacity) === 0) {
        return false;
      }
    } catch (e) {
      return true;
    }
    return true;
  }

  function escapeAttrValue(s) {
    return String(s).replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  }

  function stableSelectorForElement(el) {
    if (el.id) {
      try {
        if (typeof CSS !== "undefined" && CSS.escape) {
          return "#" + CSS.escape(el.id);
        }
      } catch (e) {}
      return "#" + String(el.id).replace(/(:|\.|\[|\]|\/|\s)/g, "\\$1");
    }
    var tag = el.tagName ? el.tagName.toLowerCase() : "*";
    var name = el.getAttribute("name");
    if (name && (tag === "input" || tag === "select" || tag === "textarea")) {
      var t = el.getAttribute("type");
      var base = tag + '[name="' + escapeAttrValue(name) + '"]';
      if (tag === "input" && t && t !== "text" && t !== "search") {
        return base + '[type="' + escapeAttrValue(t) + '"]';
      }
      return base;
    }
    var tid = el.getAttribute("data-testid");
    if (tid) {
      return '[data-testid="' + escapeAttrValue(tid) + '"]';
    }
    return tag;
  }

  /**
   * Collects visible-ish interactive controls and suggested selectors for the LLM.
   * @param options {{ maxElements?: number }}
   */
  function collectPageContext(options) {
    if (typeof document === "undefined" || !document.querySelectorAll) {
      return null;
    }
    var pageState = pageTrackerState();
    var max = (options && options.maxElements) || 60;
    var selectorList = [
      'input:not([type="hidden"])',
      "textarea",
      "select",
      "button",
      'a[href]',
      '[role="button"]',
      '[role="searchbox"]',
      '[role="textbox"]',
      '[contenteditable="true"]'
    ].join(", ");
    var nodes = document.querySelectorAll(selectorList);
    var elements = [];
    var seen = [];
    for (var i = 0; i < nodes.length && elements.length < max; i++) {
      var el = nodes[i];
      if (!el) continue;
      if (seen.indexOf(el) >= 0) continue;
      if (!isProbablyVisible(el)) continue;
      seen.push(el);
      var text = (el.innerText || el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 100);
      elements.push({
        selector: stableSelectorForElement(el),
        tag: el.tagName,
        type: el.getAttribute("type") || null,
        name: el.getAttribute("name") || null,
        id: el.id || null,
        placeholder: el.getAttribute("placeholder") || null,
        ariaLabel: el.getAttribute("aria-label") || null,
        text: text || null
      });
    }
    return {
      url: pageState.href,
      path: typeof location !== "undefined" ? location.pathname : "",
      title: document.title || "",
      pageEpoch: pageState.epoch,
      pageChanged: pageState.changed,
      collectedAt: new Date().toISOString(),
      elements: elements
    };
  }

  function navigationTargetFromArgs(args) {
    args = args || {};
    if (args.path && String(args.path).trim()) {
      var path = String(args.path).trim();
      return {
        target: path,
        path: path,
        url: null,
        external: false
      };
    }
    if (!args.url || !String(args.url).trim()) {
      return null;
    }
    var rawUrl = String(args.url).trim();
    try {
      var base = typeof window !== "undefined" && window.location ? window.location.origin : undefined;
      var parsed = new URL(rawUrl, base);
      if (typeof window !== "undefined" && window.location && parsed.origin === window.location.origin) {
        var localPath = parsed.pathname + parsed.search + parsed.hash;
        return {
          target: localPath,
          path: localPath,
          url: rawUrl,
          external: false
        };
      }
    } catch (error) {}
    return {
      target: rawUrl,
      path: null,
      url: rawUrl,
      external: true
    };
  }

  function isAlreadyOnRoute(path) {
    if (!path || typeof window === "undefined" || !window.location) {
      return false;
    }
    var current = window.location.pathname + window.location.search + window.location.hash;
    return current === path || (path.indexOf("?") === -1 && path.indexOf("#") === -1 && window.location.pathname === path);
  }

  function configuredNavigateHandler(config) {
    if (!config) {
      return null;
    }
    if (typeof config.navigate === "function") {
      return { fn: config.navigate, context: config, source: "navigate" };
    }
    if (typeof config.router === "function") {
      return { fn: config.router, context: config, source: "router" };
    }
    if (config.router && typeof config.router === "object") {
      var preferred = typeof config.routerMethod === "string" ? config.routerMethod : null;
      var methods = preferred ? [preferred, "push", "navigate", "replace"] : ["push", "navigate", "replace"];
      for (var i = 0; i < methods.length; i++) {
        var method = methods[i];
        if (typeof config.router[method] === "function") {
          return { fn: config.router[method], context: config.router, source: "router." + method };
        }
      }
    }
    return null;
  }

  function runConfiguredNavigation(config, target, args) {
    var handler = configuredNavigateHandler(config);
    if (!handler) {
      return null;
    }
    try {
      return Promise.resolve(handler.fn.call(handler.context, target.target, {
        path: target.path,
        url: target.url,
        external: target.external,
        arguments: args || {}
      })).then(function () {
        return {
          success: true,
          structuredOutput: JSON.stringify({
            path: target.path,
            url: target.url,
            routedBy: handler.source
          }),
          textSummary: "Navigated to " + target.target
        };
      }, function (error) {
        return softToolFailure("app.navigate", args, "Client router navigation failed: " + (error && error.message ? error.message : String(error)), {
          path: target.path,
          url: target.url,
          routedBy: handler.source
        });
      });
    } catch (error) {
      return Promise.resolve(softToolFailure("app.navigate", args, "Client router navigation failed: " + (error && error.message ? error.message : String(error)), {
        path: target.path,
        url: target.url,
        routedBy: handler.source
      }));
    }
  }

  function builtInTools(config) {
    return {
      "path.find": function () {
        var loc = typeof location !== "undefined" ? location : {};
        return {
          success: true,
          structuredOutput: JSON.stringify({
            path: loc.pathname || "",
            url: loc.href || "",
            title: (typeof document !== "undefined" && document.title) || "",
            search: loc.search || "",
            hash: loc.hash || ""
          }),
          textSummary: "Current path: " + (loc.pathname || "/")
        };
      },
      "app.navigate": function (args) {
        debugLog(config, "app.navigate invoked", args);
        var target = navigationTargetFromArgs(args);
        if (!target) {
          return {
            success: false,
            structuredOutput: null,
            textSummary: "No path or URL provided"
          };
        }
        if (target.path) {
          if (isAlreadyOnRoute(target.path)) {
            debugLog(config, "already on path", target.path);
            return {
              success: true,
              structuredOutput: JSON.stringify({ path: target.path, alreadyOnPage: true }),
              textSummary: "Already on " + target.path
            };
          }
        }
        var configuredNavigation = runConfiguredNavigation(config, target, args);
        if (configuredNavigation) {
          return configuredNavigation;
        }
        if (target.path) {
          history.pushState({}, "", target.path);
          window.dispatchEvent(new PopStateEvent("popstate", { state: {} }));
          return {
            success: true,
            structuredOutput: JSON.stringify({ path: target.path }),
            textSummary: "Navigated to " + target.path
          };
        }
        if (target.url) {
          window.location.assign(target.url);
          return {
            success: true,
            structuredOutput: JSON.stringify({ url: target.url }),
            textSummary: "Navigation requested"
          };
        }
        return {
          success: false,
          structuredOutput: null,
          textSummary: "No path or URL provided"
        };
      },
      "page.screenshot": function () {
        return waitForDomStable(500, 4000).then(function () {
          var loc = typeof location !== "undefined" ? location : {};
          if (config.snapshotMode === "image") {
            return loadHtml2Canvas().then(function (html2canvas) {
              return html2canvas(document.body, {
                logging: false,
                useCORS: true,
                backgroundColor: null,
                scale: 1,
                onclone: sanitizeHtml2CanvasClone
              });
            }).then(function (canvas) {
              var dataUrl = canvas.toDataURL("image/png");
              var kb = Math.round(dataUrl.length / 1024);
              return {
                success: true,
                structuredOutput: JSON.stringify({
                  mode: "image",
                  path: loc.pathname || "",
                  url: loc.href || "",
                  title: document.title || "",
                  imageBase64: dataUrl
                }),
                textSummary: "Page snapshot of " + (loc.pathname || "/") + " (image, " + kb + "KB)"
              };
            }).catch(function (error) {
              return textSnapshotResult(loc, "Image capture failed: " + (error && error.message ? error.message : String(error)));
            });
          }
          return textSnapshotResult(loc);
        });
      }
    };
  }

  function createActbrowClient(config) {
    if (!config || !config.baseUrl || !config.assistantId) {
      throw new Error("baseUrl and assistantId are required");
    }

    // Default until the runtime config endpoint replies. Tool handlers close over this
    // config object, so updating config.snapshotMode in place propagates to page.screenshot.
    if (config.snapshotMode !== "image" && config.snapshotMode !== "text") {
      config.snapshotMode = "text";
    }
    fetch(config.baseUrl + "/v1/widget/config", { method: "GET" })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (data && (data.snapshotMode === "text" || data.snapshotMode === "image")) {
          config.snapshotMode = data.snapshotMode;
          debugLog(config, "widget snapshotMode =", data.snapshotMode);
        }
      })
      .catch(function (error) {
        debugLog(config, "failed to load /v1/widget/config", error);
      });

    var emitter = createEmitter();
    var tools = builtInTools(config);

    function conversationStorageKey() {
      try {
        var base = config.baseUrl || "";
        if (typeof base === "string" && base.indexOf("/") === 0) {
          base = (typeof window !== "undefined" && window.location ? window.location.origin : "") + base;
        }
        return "actbrow:conv:" + String(base) + ":" + config.assistantId;
      } catch (e) {
        return "actbrow:conv:" + config.assistantId;
      }
    }

    var convStorageKey = conversationStorageKey();

    function readStoredConversationId() {
      try {
        return sessionStorage.getItem(convStorageKey);
      } catch (e) {
        return null;
      }
    }

    function writeStoredConversationId(id) {
      try {
        if (id) {
          sessionStorage.setItem(convStorageKey, id);
        }
      } catch (e) {}
    }

    function clearStoredConversationId() {
      try {
        sessionStorage.removeItem(convStorageKey);
      } catch (e) {}
    }

    var currentConversationId = readStoredConversationId();
    var activeRunEventSource = null;
    var activeRunId = null;

    // Persist the active runId so we can reconnect after SPA navigation
    var runStorageKey = "actbrow:run:" + config.assistantId;
    // Persist tool results that have not yet been acknowledged by the server. A hard page
    // refresh during app.navigate kills any in-flight fetch(); on the next page load we
    // replay these before reopening the SSE stream so the run history stays well-formed.
    var pendingResultsStorageKey = "actbrow:pendingResults:" + config.assistantId;

    function readStoredRunId() {
      try {
        return sessionStorage.getItem(runStorageKey);
      } catch (e) {
        return null;
      }
    }

    function writeStoredRunId(id) {
      try {
        if (id) {
          sessionStorage.setItem(runStorageKey, id);
        }
      } catch (e) {}
    }

    function clearStoredRunId() {
      try {
        sessionStorage.removeItem(runStorageKey);
      } catch (e) {}
    }

    function readPendingResults() {
      try {
        var raw = sessionStorage.getItem(pendingResultsStorageKey);
        if (!raw) return [];
        var parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
      } catch (e) {
        return [];
      }
    }

    function writePendingResults(list) {
      try {
        if (!list || list.length === 0) {
          sessionStorage.removeItem(pendingResultsStorageKey);
        } else {
          sessionStorage.setItem(pendingResultsStorageKey, JSON.stringify(list));
        }
      } catch (e) {}
    }

    function addPendingResult(runId, body) {
      var list = readPendingResults();
      list.push({ runId: runId, body: body, ts: Date.now() });
      writePendingResults(list);
    }

    function removePendingResult(toolCallId) {
      var list = readPendingResults().filter(function (entry) {
        return !entry.body || entry.body.toolCallId !== toolCallId;
      });
      writePendingResults(list);
    }

    function clearPendingResults() {
      try {
        sessionStorage.removeItem(pendingResultsStorageKey);
      } catch (e) {}
    }

    function normalizeApiKey(key) {
      if (key == null) {
        return "";
      }
      var s = String(key).trim();
      return s;
    }

    var resolvedApiKey = normalizeApiKey(config.apiKey);

    function request(path, options) {
      debugLog(config, "request", path, options && options.method ? options.method : "GET");

      // Copy headers so we never mutate the caller's object; add auth when key is set
      var headers = options && options.headers ? Object.assign({}, options.headers) : {};
      if (resolvedApiKey) {
        headers["Authorization"] = "Bearer " + resolvedApiKey;
        headers["X-API-Key"] = resolvedApiKey;
      }
      if (!headers["Content-Type"]) {
        headers["Content-Type"] = "application/json";
      }
      
      var fetchOptions = Object.assign({}, options, {
        headers: headers
      });
      
      return fetch(config.baseUrl + path, fetchOptions).then(function (response) {
        if (!response.ok) {
          return response.json().catch(function () { return {}; }).then(function (body) {
            debugLog(config, "request failed", path, response.status, body);
            throw new Error(body.error || ("Request failed with status " + response.status));
          });
        }
        if (response.status === 204) {
          debugLog(config, "request complete", path, response.status);
          return null;
        }
        return response.json().then(function (body) {
          debugLog(config, "request complete", path, response.status, body);
          return body;
        });
      });
    }

    function handleRunEvent(runId, event) {
      var data = JSON.parse(event.data);
      debugLog(config, "run event", runId, data.eventType, data.payload);
      emitter.emit(data.eventType, data);
      if (data.eventType === "tool.call.requested" && data.payload.type === "BROWSER_HTTP") {
        var browserPayload = data.payload;
        Promise.resolve()
          .then(function () {
            return executeBrowserHttpTool(browserPayload);
          })
          .then(function (result) {
            return postToolResult(runId, {
              toolCallId: browserPayload.toolCallId,
              success: result.success !== false,
              structuredOutput: result.structuredOutput || null,
              textSummary: result.textSummary || null,
              error: result.error || null
            });
          })
          .catch(function (error) {
            debugLog(config, "browser http tool failed", browserPayload.toolKey, error);
            return postToolResult(runId, {
              toolCallId: browserPayload.toolCallId,
              success: false,
              error: error && error.message ? error.message : String(error)
            });
          });
        return;
      }
      if (data.eventType === "tool.call.requested" && data.payload.type === "CLIENT") {
        var resolvedToolKey = data.payload.executorKey || data.payload.toolKey;
        var handler = tools[resolvedToolKey];
        if (!handler) {
          debugLog(config, "missing client tool handler", resolvedToolKey);
          postToolResult(runId, {
            toolCallId: data.payload.toolCallId,
            success: false,
            error: "No client tool registered for " + resolvedToolKey
          });
          return;
        }
        Promise.resolve()
          .then(function () {
            debugLog(config, "executing client tool", resolvedToolKey, data.payload.arguments || {});
            return handler(data.payload.arguments || {});
          })
          .then(function (result) {
            debugLog(config, "client tool result", resolvedToolKey, result);
            return postToolResult(runId, {
              toolCallId: data.payload.toolCallId,
              success: result.success !== false,
              structuredOutput: result.structuredOutput || null,
              textSummary: result.textSummary || null,
              error: result.error || null
            });
          })
          .catch(function (error) {
            debugLog(config, "client tool failed", resolvedToolKey, error);
            return postToolResult(runId, {
              toolCallId: data.payload.toolCallId,
              success: false,
              error: error.message
            });
          });
      }
    }

    function executeBrowserHttpTool(payload) {
      var http = payload.http || {};
      var method = String(http.method || "GET").toUpperCase();
      var baseUrl = String(http.baseUrl || "");
      var path = String(http.path || "/");
      var target = new URL(joinBrowserHttpUrl(baseUrl, path), window.location.origin);
      if (!http.allowCrossOrigin && target.origin !== window.location.origin) {
        throw new Error("Browser HTTP tool target must be same-origin unless metadata.allowCrossOrigin is true");
      }

      var headers = {};
      var rawHeaders = isObjectRecord(http.headers) ? http.headers : {};
      Object.keys(rawHeaders).forEach(function (name) {
        var normalized = name.toLowerCase();
        if (forbiddenBrowserHttpHeaders[normalized]) return;
        // Never trust a server-stored Authorization header — the credential must come from the
        // embedding page (see config.getRequestHeaders below), not from saved tool metadata.
        if (normalized === "authorization") return;
        headers[name] = String(rawHeaders[name]);
      });
      if (method !== "GET" && method !== "HEAD" && !headers["Content-Type"]) {
        headers["Content-Type"] = "application/json";
      }

      // Per-request header hook: lets the embedder inject auth from page state (e.g. a Bearer
      // token kept in localStorage) at fetch time. Applied last so it wins, and may set
      // Authorization. The same-origin guard above ensures these headers never go cross-origin
      // unless the tool explicitly opted into allowCrossOrigin.
      if (typeof config.getRequestHeaders === "function") {
        try {
          var dynamicHeaders = config.getRequestHeaders(payload.toolKey, target) || {};
          Object.keys(dynamicHeaders).forEach(function (name) {
            if (forbiddenBrowserHttpHeaders[name.toLowerCase()]) return;
            headers[name] = String(dynamicHeaders[name]);
          });
        }
        catch (hookError) {
          debugLog(config, "getRequestHeaders hook threw", hookError);
        }
      }

      var init = {
        method: method,
        credentials: http.credentials || "include",
        headers: headers
      };
      if (method !== "GET" && method !== "HEAD") {
        // Prefer the server-shaped body (path/query params already lifted out for
        // OpenAPI-generated tools); fall back to the raw arguments for legacy HTTP tools.
        var requestBody = (http.body !== undefined && http.body !== null) ? http.body : (payload.arguments || {});
        init.body = JSON.stringify(requestBody);
      }

      debugLog(config, "executing browser http tool", method, target.toString());
      return fetch(target.toString(), init).then(function (response) {
        var contentType = response.headers.get("content-type") || "";
        var bodyPromise = contentType.indexOf("application/json") >= 0
          ? response.json().catch(function () { return null; })
          : response.text().catch(function () { return ""; });
        return bodyPromise.then(function (responseBody) {
          var structuredOutput = JSON.stringify({
            status: response.status,
            ok: response.ok,
            body: responseBody
          });
          return {
            success: response.ok,
            structuredOutput: structuredOutput,
            textSummary: "Browser HTTP " + method + " " + target.pathname + " returned " + response.status,
            error: response.ok ? null : structuredOutput
          };
        });
      });
    }

    function postToolResult(runId, body) {
      // Persist BEFORE firing the fetch so a mid-flight page unload (e.g. app.navigate
      // hard-refresh) does not drop the tool result. On success we remove the entry.
      addPendingResult(runId, body);
      return request("/v1/runs/" + runId + "/tool-results", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
      }).then(function (response) {
        removePendingResult(body.toolCallId);
        return response;
      }).catch(function (error) {
        // Leave the entry in sessionStorage; flushPendingToolResults will retry on next boot.
        debugLog(config, "postToolResult failed, will retry on boot", error);
        throw error;
      });
    }

    function flushPendingToolResults() {
      var list = readPendingResults();
      if (!list.length) return Promise.resolve();
      debugLog(config, "flushing pending tool results", list.length);
      // Drain sequentially so order is preserved for the same run.
      return list.reduce(function (prev, entry) {
        return prev.then(function () {
          return fetch(config.baseUrl + "/v1/runs/" + entry.runId + "/tool-results", {
            method: "POST",
            headers: (function () {
              var h = { "Content-Type": "application/json" };
              if (resolvedApiKey) {
                h["Authorization"] = "Bearer " + resolvedApiKey;
                h["X-API-Key"] = resolvedApiKey;
              }
              return h;
            })(),
            body: JSON.stringify(entry.body)
          }).then(function (response) {
            // On 4xx (run deleted, conversation gone, already completed), drop the entry.
            // Otherwise it would replay on every page load forever.
            if (response.ok || (response.status >= 400 && response.status < 500)) {
              removePendingResult(entry.body.toolCallId);
            } else {
              debugLog(config, "pending result replay got transient status, keeping", response.status);
            }
          }).catch(function (error) {
            debugLog(config, "pending result replay network error, keeping", entry.body.toolCallId, error);
          });
        });
      }, Promise.resolve());
    }

    function streamRun(runId) {
      if (activeRunEventSource) {
        try {
          activeRunEventSource.close();
        } catch (e) {}
        activeRunEventSource = null;
      }
      // EventSource cannot send Authorization / X-API-Key; server accepts ?apiKey= for this path
      var eventsUrl = config.baseUrl + "/v1/runs/" + runId + "/events";
      if (resolvedApiKey) {
        eventsUrl += (eventsUrl.indexOf("?") >= 0 ? "&" : "?") + "apiKey=" + encodeURIComponent(resolvedApiKey);
      }
      var source = new EventSource(eventsUrl);
      activeRunEventSource = source;
      activeRunId = runId;
      writeStoredRunId(runId);
      debugLog(config, "opening run stream", runId);
      source.onopen = function () {
        debugLog(config, "run stream open", runId);
      };
      function endStream() {
        if (activeRunEventSource === source) {
          activeRunEventSource = null;
        }
        if (activeRunId === runId) {
          activeRunId = null;
        }
        clearStoredRunId();
        try {
          source.close();
        } catch (e) {}
      }
      ["run.started", "tool.call.requested", "tool.call.completed", "assistant.message.completed", "run.failed", "run.cancelled"]
        .forEach(function (eventName) {
          source.addEventListener(eventName, function (event) {
            handleRunEvent(runId, event);
            if (eventName === "assistant.message.completed" || eventName === "run.failed" || eventName === "run.cancelled") {
              endStream();
            }
          });
        });
      source.onerror = function (event) {
        debugLog(config, "run stream error", runId, event);
      };
      return source;
    }

    return {
      registerTool: function (name, schema, handler) {
        tools[name] = handler;
        emitter.emit("tool.registered", { name: name, schema: schema || null });
      },
      syncTools: function () {
        return request("/v1/assistants/" + config.assistantId + "/tools", {
          method: "GET"
        });
      },
      startConversation: function () {
        return request("/v1/conversations", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ assistantId: config.assistantId })
        }).then(function (conversation) {
          currentConversationId = conversation.id;
          writeStoredConversationId(conversation.id);
          return conversation;
        });
      },
      listMessages: function (conversationId) {
        return request("/v1/conversations/" + conversationId + "/messages", {
          method: "GET"
        });
      },
      getConversationId: function () {
        return currentConversationId;
      },
      /** Re-read the persisted conversation id (e.g. after navigation or bfcache restore). */
      syncConversationIdFromStorage: function () {
        currentConversationId = readStoredConversationId();
      },
      clearStoredConversation: function () {
        if (activeRunEventSource) {
          try {
            activeRunEventSource.close();
          } catch (e) {}
          activeRunEventSource = null;
        }
        clearStoredConversationId();
        clearStoredRunId();
        clearPendingResults();
        currentConversationId = null;
      },
      /**
       * Closes the run event stream, clears local persistence immediately (so navigation / reopen cannot
       * resurrect the old thread), then DELETE on the server. Server errors are ignored.
       *
       * Also clears any pending tool results queued for the previous run — otherwise the new chat
       * would replay them against a deleted conversation, causing 404s and leaked storage.
       */
      resetConversationForNewChat: function () {
        if (activeRunEventSource) {
          try {
            activeRunEventSource.close();
          } catch (e) {}
          activeRunEventSource = null;
        }
        var id = currentConversationId || readStoredConversationId();
        clearStoredConversationId();
        clearStoredRunId();
        clearPendingResults();
        currentConversationId = null;
        if (!id) {
          return Promise.resolve();
        }
        return request("/v1/conversations/" + encodeURIComponent(id), { method: "DELETE" }).catch(function () {
          return null;
        });
      },
      sendMessage: function (message) {
        var content;
        var pageContextPayload = undefined;
        var maxEl = (config && config.pageContextMaxElements) || 60;
        if (typeof message === "string") {
          content = message;
          if (config && config.includePageContext === false) {
            pageContextPayload = null;
          } else {
            pageContextPayload = collectPageContext({ maxElements: maxEl });
          }
        } else if (message && typeof message === "object") {
          content = message.content;
          if (message.includePageContext === false) {
            pageContextPayload = null;
          } else if (message.pageContext != null) {
            pageContextPayload = message.pageContext;
          } else {
            pageContextPayload = collectPageContext({ maxElements: maxEl });
          }
        } else {
          throw new Error("sendMessage expects a string or { content: string, ... }");
        }
        var body = { content: content };
        if (pageContextPayload != null) {
          body.pageContext = pageContextPayload;
        }
        if (!currentConversationId) {
          currentConversationId = readStoredConversationId();
        }
        var ensureConversation = currentConversationId
          ? Promise.resolve({ id: currentConversationId })
          : this.startConversation();
        return ensureConversation.then(function (conversation) {
          return request("/v1/conversations/" + conversation.id + "/turns", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body)
          }).then(function (run) {
            streamRun(run.id);
            return run;
          });
        });
      },
      collectPageContext: function (opts) {
        var maxEl = (opts && opts.maxElements) || (config && config.pageContextMaxElements) || 60;
        return collectPageContext({ maxElements: maxEl });
      },
      ensureConversation: function () {
        return currentConversationId
          ? Promise.resolve({ id: currentConversationId })
          : this.startConversation();
      },
      /**
       * Re-subscribe to an in-progress run SSE stream after SPA navigation.
       * Safe to call on every page load; no-ops if no run is stored or stream is already open.
       *
       * Flushes any tool results that were persisted but not acknowledged on the previous page
       * (e.g. a hard-refresh killed the fetch mid-flight) before reopening the stream. This
       * unblocks the server-side run loop so the next model step can proceed.
       */
      syncRunFromStorage: function () {
        var storedRunId = readStoredRunId();
        if (!storedRunId || activeRunEventSource) {
          return;
        }
        debugLog(config, "reconnecting to run after navigation", storedRunId);
        flushPendingToolResults().then(function () {
          if (!activeRunEventSource) {
            streamRun(storedRunId);
          }
        });
      },
      getActiveRunId: function () {
        return activeRunId || readStoredRunId();
      },
      cancelRun: function () {
        var id = activeRunId || readStoredRunId();
        if (!id) {
          return Promise.resolve(null);
        }
        debugLog(config, "cancelling run", id);
        return request("/v1/runs/" + id + "/cancel", { method: "POST" })
          .catch(function (error) {
            debugLog(config, "cancel run failed", id, error);
            return null;
          });
      },
      on: emitter.on
    };
  }

  function createActbrowWidget(config) {
    if (!config || !config.baseUrl || !config.assistantId) {
      throw new Error("baseUrl and assistantId are required");
    }

    injectStyles([
      "/* ===== ActBrow Widget - Transparent Glassmorphism UI ===== */",
      "@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap');",
      ".actbrow-widget-root{position:fixed;inset:0;z-index:2147483000;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;-webkit-font-smoothing:antialiased;-moz-osx-font-smoothing:grayscale;pointer-events:none;}",
      
      "/* Launcher Button - Solid Color */",
      ".actbrow-widget-launcher{position:fixed;right:24px;bottom:24px;pointer-events:auto;z-index:2147483002;width:56px;height:56px;border:none;border-radius:999px;background:#1a1a1a;color:#fff;font-weight:600;font-size:13px;letter-spacing:.02em;cursor:pointer;box-shadow:0 8px 32px rgba(0,0,0,.2);transition:all .3s cubic-bezier(.4,0,.2,1);outline:none;overflow:hidden;border:1px solid rgba(0,0,0,.15);}",
      ".actbrow-widget-launcher:hover{transform:translateY(-3px) scale(1.02);background:#222;box-shadow:0 16px 48px rgba(0,0,0,.25);}",
      ".actbrow-widget-launcher:active{transform:translateY(-1px) scale(.98);box-shadow:0 8px 24px rgba(0,0,0,.2);}",
      ".actbrow-widget-launcher:focus-visible{box-shadow:0 0 0 4px rgba(0,0,0,.15),0 8px 32px rgba(0,0,0,.2);}",
      ".actbrow-widget-launcher::before{content:'';position:absolute;inset:0;border-radius:inherit;background:linear-gradient(135deg,rgba(255,255,255,.15) 0%,rgba(255,255,255,.05) 100%);pointer-events:none;}",
      ".actbrow-widget-launcher::after{content:'';position:absolute;inset:-3px;border-radius:inherit;border:1px solid rgba(255,255,255,.15);animation:actbrow-launcher-pulse 2s ease-out infinite;pointer-events:none;}",
      ".actbrow-widget-launcher-icon{display:flex;align-items:center;justify-content:center;width:100%;height:100%;position:relative;z-index:1;}",
      ".actbrow-widget-launcher-icon svg{width:24px;height:24px;fill:currentColor;filter:drop-shadow(0 2px 4px rgba(0,0,0,.2));}",
      
      "/* Chat Panel - Dark Theme matching actbrow UI */",
      ".actbrow-widget-panel{position:fixed;pointer-events:auto;z-index:2147483001;width:340px;max-width:480px;max-height:640px;height:480px;min-width:280px;min-height:320px;display:flex;flex-direction:column;border-radius:20px;overflow:hidden;background:linear-gradient(180deg,#1a1a2e 0%,#0f0f1a 100%);border:1px solid rgba(255,255,255,.1);box-shadow:0 32px 96px rgba(0,0,0,.4),0 8px 24px rgba(0,0,0,.2);transition:opacity .3s cubic-bezier(.4,0,.2,1),transform .3s cubic-bezier(.4,0,.2,1);transform-origin:center bottom;}",
      ".actbrow-widget-panel.actbrow-widget-hidden{opacity:0;transform:scale(.92) translateY(12px);pointer-events:none;}",
      ".actbrow-widget-panel:not(.actbrow-widget-hidden){opacity:1;transform:scale(1) translateY(0);}",
      ".actbrow-widget-panel.actbrow-widget-dragging,.actbrow-widget-panel.actbrow-widget-resizing{transition:none;user-select:none;}",
      ".actbrow-widget-resize-handle{position:absolute;width:18px;height:18px;z-index:3;touch-action:none;}",
      ".actbrow-widget-resize-handle-tl{top:0;left:0;cursor:nwse-resize;border-radius:20px 0 12px 0;background:linear-gradient(135deg,rgba(255,255,255,.12) 0%,transparent 55%);}",
      ".actbrow-widget-resize-handle-tl::after{content:'';position:absolute;top:5px;left:5px;width:8px;height:8px;border-top:2px solid rgba(255,255,255,.35);border-left:2px solid rgba(255,255,255,.35);border-radius:2px 0 0 0;}",
      ".actbrow-widget-resize-handle-br{bottom:0;right:0;cursor:nwse-resize;border-radius:0 0 20px 0;background:linear-gradient(315deg,rgba(255,255,255,.12) 0%,transparent 55%);}",
      ".actbrow-widget-resize-handle-br::after{content:'';position:absolute;bottom:5px;right:5px;width:8px;height:8px;border-bottom:2px solid rgba(255,255,255,.35);border-right:2px solid rgba(255,255,255,.35);border-radius:0 0 2px 0;}",

      "/* Header - Dark gradient */",
      ".actbrow-widget-header{padding:14px 14px 12px;background:linear-gradient(180deg,rgba(255,255,255,.08) 0%,transparent 100%);color:#e5e5e5;display:flex;justify-content:space-between;align-items:flex-start;gap:10px;border-bottom:1px solid rgba(255,255,255,.08);position:relative;cursor:grab;touch-action:none;}",
      ".actbrow-widget-panel.actbrow-widget-dragging .actbrow-widget-header{cursor:grabbing;}",
      ".actbrow-widget-header::after{content:'';position:absolute;bottom:0;left:0;right:0;height:1px;background:linear-gradient(90deg,transparent 0%,rgba(255,255,255,.06) 50%,transparent 100%);}",
      ".actbrow-widget-header-content{flex:1;min-width:0;}",
      ".actbrow-widget-title{font-size:13px;font-weight:700;letter-spacing:-.02em;color:#e5e5e5;margin-bottom:3px;display:flex;align-items:center;gap:7px;font-family:'Inter',sans-serif;}",
      ".actbrow-widget-title-icon{width:18px;height:18px;background:linear-gradient(135deg,#ffffff 0%,#a0a0a0 100%);border-radius:5px;display:flex;align-items:center;justify-content:center;box-shadow:0 2px 6px rgba(0,0,0,.3);}",
      ".actbrow-widget-title-icon svg{width:11px;height:11px;fill:#000;}",
      ".actbrow-widget-subtitle{font-size:11px;color:#a0a0a0;line-height:1.4;max-width:220px;font-weight:400;}",
      ".actbrow-widget-badge{display:inline-flex;align-items:center;gap:5px;margin-top:8px;padding:3px 8px;background:rgba(34,197,94,.15);border-radius:999px;border:1px solid rgba(34,197,94,.3);}",
      ".actbrow-widget-badge-dot{width:6px;height:6px;border-radius:999px;background:#22c55e;box-shadow:0 0 0 3px rgba(34,197,94,.2);animation:actbrow-badge-pulse 2s ease-in-out infinite;}",
      ".actbrow-widget-badge-text{font-size:9px;font-weight:600;color:#4ade80;letter-spacing:.02em;}",
      ".actbrow-widget-header-actions{display:flex;flex-direction:column;align-items:flex-end;gap:6px;flex-shrink:0;cursor:default;}",
      ".actbrow-widget-clear-history{border:1px solid rgba(255,255,255,.12);border-radius:10px;background:rgba(255,255,255,.06);color:#b0b0b0;font-size:11px;font-weight:600;letter-spacing:.02em;cursor:pointer;padding:6px 10px;line-height:1.2;transition:all .2s ease;font-family:'Inter',sans-serif;white-space:nowrap;}",
      ".actbrow-widget-clear-history:hover{background:rgba(255,255,255,.1);color:#e5e5e5;border-color:rgba(255,255,255,.18);}",
      ".actbrow-widget-clear-history:active{transform:scale(.98);}",
      ".actbrow-widget-clear-history:disabled{opacity:.45;cursor:not-allowed;transform:none;}",
      ".actbrow-widget-close{background:rgba(255,255,255,.08);border:none;color:#a0a0a0;font-size:20px;cursor:pointer;line-height:1;padding:6px;border-radius:10px;transition:all .2s ease;display:flex;align-items:center;justify-content:center;width:32px;height:32px;}",
      ".actbrow-widget-close:hover{background:rgba(255,255,255,.12);color:#e5e5e5;transform:rotate(90deg);}",
      ".actbrow-widget-close:active{transform:rotate(90deg) scale(.95);}",

      "/* Messages Area */",
      ".actbrow-widget-body{flex:1;display:flex;flex-direction:column;min-height:0;background:transparent;}",
      ".actbrow-widget-messages{flex:1;overflow-y:auto;overflow-x:hidden;padding:12px 12px 10px;display:flex;flex-direction:column;gap:10px;scroll-behavior:smooth;}",
      ".actbrow-widget-messages::-webkit-scrollbar{width:6px;}",
      ".actbrow-widget-messages::-webkit-scrollbar-track{background:transparent;}",
      ".actbrow-widget-messages::-webkit-scrollbar-thumb{background:rgba(255,255,255,.2);border-radius:999px;transition:background .2s;}",
      ".actbrow-widget-messages::-webkit-scrollbar-thumb:hover{background:rgba(255,255,255,.3);}",

      "/* Empty State */",
      ".actbrow-widget-empty{padding:16px;border-radius:16px;background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.1);color:#ccc;text-align:center;}",
      ".actbrow-widget-empty-title{font-size:12px;font-weight:600;color:#e5e5e5;margin-bottom:6px;font-family:'Inter',sans-serif;}",
      ".actbrow-widget-empty-desc{font-size:11px;color:#a0a0a0;line-height:1.5;margin-bottom:12px;font-weight:400;}",
      ".actbrow-widget-suggestions{display:flex;flex-direction:column;gap:6px;}",
      ".actbrow-widget-suggestion{border:none;border-radius:12px;background:rgba(255,255,255,.05);color:#e5e5e5;padding:10px 12px;font-size:12px;font-weight:500;cursor:pointer;border:1px solid rgba(255,255,255,.12);box-shadow:0 2px 6px rgba(0,0,0,.2);transition:all .2s ease;text-align:left;display:flex;align-items:center;gap:8px;font-family:'Inter',sans-serif;}",
      ".actbrow-widget-suggestion:hover{background:rgba(255,255,255,.1);border-color:rgba(255,255,255,.2);box-shadow:0 3px 8px rgba(0,0,0,.3);transform:translateY(-1px);}",
      ".actbrow-widget-suggestion:active{transform:translateY(0) scale(.98);}",
      ".actbrow-widget-suggestion-icon{width:18px;height:18px;background:rgba(255,255,255,.1);border-radius:6px;display:flex;align-items:center;justify-content:center;border:1px solid rgba(255,255,255,.1);}",
      ".actbrow-widget-suggestion-icon svg{width:11px;height:11px;fill:#e5e5e5;}",
      
      "/* Message Rows */",
      ".actbrow-widget-row{display:flex;flex-direction:column;gap:4px;animation:actbrow-message-slide .3s ease-out;}",
      ".actbrow-widget-row-user{align-items:flex-end;}",
      ".actbrow-widget-row-assistant,.actbrow-widget-row-system{align-items:flex-start;}",
      "@keyframes actbrow-message-slide{from{opacity:0;transform:translateY(12px);}to{opacity:1;transform:translateY(0);}}",
      ".actbrow-widget-label{font-size:8px;color:#999;font-weight:600;letter-spacing:.08em;text-transform:uppercase;padding:0 3px;font-family:'Inter',sans-serif;}",
      
      "/* Message Bubbles */",
      ".actbrow-widget-message{max-width:85%;padding:10px 13px;border-radius:16px;font-size:13px;line-height:1.5;white-space:pre-wrap;word-break:break-word;position:relative;font-family:'Inter',sans-serif;}",
      ".actbrow-widget-message-body{white-space:pre-wrap;word-break:break-word;}",
      ".actbrow-widget-message-options{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px;}",
      ".actbrow-widget-message-option{border:none;border-radius:999px;background:rgba(255,255,255,.08);color:#e5e5e5;padding:8px 12px;font-size:12px;font-weight:600;cursor:pointer;border:1px solid rgba(255,255,255,.12);transition:all .2s ease;font-family:'Inter',sans-serif;}",
      ".actbrow-widget-message-option:hover{background:rgba(255,255,255,.14);border-color:rgba(255,255,255,.22);}",
      ".actbrow-widget-message-option-recommended{background:rgba(34,197,94,.14);border-color:rgba(34,197,94,.35);color:#86efac;}",
      ".actbrow-widget-message-option-recommended:hover{background:rgba(34,197,94,.2);border-color:rgba(34,197,94,.45);}",
      ".actbrow-widget-message-user{background:linear-gradient(135deg,#ffffff 0%,#d0d0d0 100%);color:#000;border-bottom-right-radius:5px;box-shadow:0 4px 16px rgba(0,0,0,.3);}",
      ".actbrow-widget-message-assistant{background:rgba(255,255,255,.08);color:#e5e5e5;border:1px solid rgba(255,255,255,.1);border-bottom-left-radius:5px;}",
      ".actbrow-widget-message a{color:#e5e5e5;text-decoration:underline;text-underline-offset:3px;}",
      ".actbrow-widget-message a:hover{text-decoration:none;opacity:.7;}",
      ".actbrow-widget-message code{background:rgba(255,255,255,.12);padding:2px 5px;border-radius:4px;font-family:'JetBrains Mono',monospace;font-size:11px;}",
      ".actbrow-widget-message pre{background:#0a0a0a;color:#d4d4d4;padding:10px;border-radius:10px;overflow-x:auto;margin:6px 0;font-size:11px;border:1px solid rgba(255,255,255,.08);}",
      ".actbrow-widget-message pre code{background:transparent;padding:0;}",

      "/* Typing Indicator - Wave Animation */",
      ".actbrow-widget-message-thinking{display:flex;align-items:center;gap:4px;padding:10px 14px;background:rgba(255,255,255,.08);border-radius:16px;border:1px solid rgba(255,255,255,.08);}",
      ".actbrow-widget-thinking-dot{width:7px;height:7px;border-radius:999px;background:#e5e5e5;animation:actbrow-dot-wave 1.4s ease-in-out infinite;}",
      ".actbrow-widget-thinking-dot:nth-child(1){animation-delay:0s;}",
      ".actbrow-widget-thinking-dot:nth-child(2){animation-delay:0.15s;}",
      ".actbrow-widget-thinking-dot:nth-child(3){animation-delay:0.3s;}",
      "@keyframes actbrow-dot-wave{0%,40%,100%{transform:translateY(0);opacity:.6;}20%{transform:translateY(-6px);opacity:1;box-shadow:0 3px 8px rgba(0,0,0,.3);}}",

      "/* Tool Steps Group (collapsible per-turn) */",
      ".actbrow-widget-steps{max-width:95%;width:95%;border-radius:14px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.08);overflow:hidden;}",
      ".actbrow-widget-steps-header{display:flex;align-items:center;gap:8px;padding:9px 11px;cursor:pointer;user-select:none;font-family:'Inter',sans-serif;color:#c8c8c8;font-size:11px;font-weight:600;letter-spacing:.01em;background:transparent;border:none;width:100%;text-align:left;transition:background .15s ease;}",
      ".actbrow-widget-steps-header:hover{background:rgba(255,255,255,.04);}",
      ".actbrow-widget-steps-header:focus-visible{outline:none;background:rgba(255,255,255,.06);}",
      ".actbrow-widget-steps-icon{width:14px;height:14px;display:flex;align-items:center;justify-content:center;color:#9ca3af;flex-shrink:0;}",
      ".actbrow-widget-steps-icon svg{width:14px;height:14px;fill:currentColor;}",
      ".actbrow-widget-steps.is-running .actbrow-widget-steps-icon{color:#60a5fa;animation:actbrow-spin 1s linear infinite;}",
      ".actbrow-widget-steps.is-error .actbrow-widget-steps-icon{color:#f87171;animation:none;}",
      ".actbrow-widget-steps-title{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}",
      ".actbrow-widget-steps-chevron{width:12px;height:12px;color:#9ca3af;transition:transform .2s ease;flex-shrink:0;display:flex;align-items:center;justify-content:center;}",
      ".actbrow-widget-steps-chevron svg{width:12px;height:12px;fill:currentColor;}",
      ".actbrow-widget-steps.is-open .actbrow-widget-steps-chevron{transform:rotate(180deg);}",
      ".actbrow-widget-steps-list{display:none;flex-direction:column;padding:2px 11px 10px;border-top:1px solid rgba(255,255,255,.06);}",
      ".actbrow-widget-steps.is-open .actbrow-widget-steps-list{display:flex;}",
      ".actbrow-widget-step{display:flex;align-items:flex-start;gap:8px;padding:7px 0;font-family:'Inter',sans-serif;}",
      ".actbrow-widget-step + .actbrow-widget-step{border-top:1px dashed rgba(255,255,255,.05);}",
      ".actbrow-widget-step-status{width:14px;height:14px;flex-shrink:0;display:flex;align-items:center;justify-content:center;margin-top:1px;color:#9ca3af;}",
      ".actbrow-widget-step-status svg{width:12px;height:12px;fill:currentColor;}",
      ".actbrow-widget-step.is-running .actbrow-widget-step-status{color:#60a5fa;animation:actbrow-spin 1s linear infinite;}",
      ".actbrow-widget-step.is-done .actbrow-widget-step-status{color:#22c55e;}",
      ".actbrow-widget-step.is-error .actbrow-widget-step-status{color:#f87171;}",
      ".actbrow-widget-step-body{flex:1;min-width:0;}",
      ".actbrow-widget-step-name{font-size:11px;font-weight:600;color:#e5e5e5;line-height:1.4;font-family:'JetBrains Mono',monospace;word-break:break-all;}",
      ".actbrow-widget-step-meta{font-size:10px;color:#9ca3af;line-height:1.4;margin-top:2px;word-break:break-word;font-family:'JetBrains Mono',monospace;overflow-wrap:anywhere;}",
      "@keyframes actbrow-spin{from{transform:rotate(0)}to{transform:rotate(360deg)}}",
      
      "/* Status Bar */",
      ".actbrow-widget-status{padding:0 14px 10px;color:#666;font-size:10px;min-height:18px;display:flex;align-items:center;gap:6px;font-family:'Inter',sans-serif;}",
      ".actbrow-widget-status-dot{width:5px;height:5px;border-radius:999px;background:#22c55e;box-shadow:0 0 0 3px rgba(34,197,94,.15);animation:actbrow-status-pulse 1.5s ease-in-out infinite;}",
      "@keyframes actbrow-status-pulse{0%,100%{opacity:1;}50%{opacity:.4;}}",
      
      "/* Black Footer Section */",
      ".actbrow-widget-footer{background:#1a1a1a;border-top:1px solid rgba(0,0,0,.2);padding:12px 14px;}",
      ".actbrow-widget-powered{font-size:9px;color:#888;font-weight:600;letter-spacing:.06em;display:flex;align-items:center;justify-content:center;gap:5px;font-family:'Inter',sans-serif;margin-top:10px;}",
      ".actbrow-widget-powered svg{width:12px;height:12px;fill:#888;}",
      
      "/* Input Form */",
      ".actbrow-widget-form-wrap{padding:0;background:transparent;}",
      ".actbrow-widget-form{display:flex;gap:6px;padding:5px;border:1px solid rgba(255,255,255,.15);border-radius:16px;background:#2a2a2a;box-shadow:0 2px 6px rgba(0,0,0,.2);transition:all .2s ease;align-items:flex-end;}",
      ".actbrow-widget-form:focus-within{border-color:rgba(255,255,255,.3);box-shadow:0 3px 12px rgba(0,0,0,.3),0 0 0 3px rgba(255,255,255,.08);}",
      ".actbrow-widget-input{flex:1;border:none;background:#3a3a3a;padding:9px 12px;font-size:13px;color:#fff;outline:none;font-family:'Inter',sans-serif;border-radius:12px;resize:none;overflow-y:auto;min-height:38px;max-height:160px;line-height:1.45;field-sizing:content;}",
      ".actbrow-widget-input::placeholder{color:#888;}",
      ".actbrow-widget-input:focus{background:#404040;}",
      ".actbrow-widget-send{border:none;border-radius:14px;background:#fff;color:#000;padding:0 16px;font-weight:600;cursor:pointer;min-width:50px;height:38px;font-size:12px;display:flex;align-items:center;justify-content:center;gap:6px;transition:all .2s ease;font-family:'Inter',sans-serif;}",
      ".actbrow-widget-send:hover{transform:translateY(-1px);box-shadow:0 3px 10px rgba(255,255,255,.3);background:#f8f8f8;}",
      ".actbrow-widget-send:active{transform:translateY(0) scale(.98);}",
      ".actbrow-widget-send:disabled,.actbrow-widget-input:disabled{opacity:.5;cursor:not-allowed;transform:none;box-shadow:none;}",
      ".actbrow-widget-send svg{width:16px;height:16px;fill:currentColor;}",
      
      "/* Animations */",
      "@keyframes actbrow-launcher-pulse{0%{transform:scale(1);opacity:.4;}70%{transform:scale(1.15);opacity:0;}100%{transform:scale(1.15);opacity:0;}}",
      "@keyframes actbrow-badge-pulse{0%,100%{box-shadow:0 0 0 4px rgba(34,197,94,.15);}50%{box-shadow:0 0 0 8px rgba(34,197,94,.08);}}",
      
      "/* Responsive Design */",
      "@media (max-width:640px){.actbrow-widget-launcher{right:12px;bottom:12px;width:52px;height:52px;}.actbrow-widget-panel{max-width:calc(100vw - 24px) !important;max-height:min(640px,calc(100vh - 24px)) !important;border-radius:16px;}.actbrow-widget-message{max-width:90%;}.actbrow-widget-steps{max-width:96%;width:96%;}}",
      
      "/* Reduced Motion */",
      "@media (prefers-reduced-motion:reduce){.actbrow-widget-launcher,.actbrow-widget-panel,.actbrow-widget-row,.actbrow-widget-close,.actbrow-widget-suggestion,.actbrow-widget-send{transition:none;animation:none;}.actbrow-widget-thinking-dot{animation:none;}}",
      
      "/* Dark Mode Support */",
      "@media (prefers-color-scheme:dark){.actbrow-widget-launcher{background:#fff;color:#000;box-shadow:0 8px 32px rgba(255,255,255,.15);}.actbrow-widget-launcher:hover{background:#f5f5f5;}.actbrow-widget-launcher::before{background:linear-gradient(135deg,rgba(0,0,0,.1) 0%,rgba(0,0,0,.02) 100%);}.actbrow-widget-launcher::after{border-color:rgba(0,0,0,.15);}.actbrow-widget-panel{background:#1e1e1e;border-color:rgba(255,255,255,.08);box-shadow:0 32px 96px rgba(0,0,0,.4),0 8px 24px rgba(0,0,0,.2);}.actbrow-widget-header{background:#252525;color:#e5e5e5;border-color:rgba(255,255,255,.08);}.actbrow-widget-header::after{background:linear-gradient(90deg,transparent 0%,rgba(255,255,255,.06) 50%,transparent 100%);}.actbrow-widget-title{color:#e5e5e5;}.actbrow-widget-title-icon{background:#e5e5e5;}.actbrow-widget-title-icon svg{fill:#000;}.actbrow-widget-subtitle{color:#aaa;}.actbrow-widget-badge{background:rgba(34,197,94,.2);border-color:rgba(34,197,94,.3);}.actbrow-widget-badge-text{color:#4ade80;}.actbrow-widget-close{background:rgba(255,255,255,.08);color:#aaa;}.actbrow-widget-close:hover{background:rgba(255,255,255,.12);color:#e5e5e5;}.actbrow-widget-messages::-webkit-scrollbar-thumb{background:rgba(255,255,255,.2);}.actbrow-widget-messages::-webkit-scrollbar-thumb:hover{background:rgba(255,255,255,.3);}.actbrow-widget-empty{background:#2a2a2a;border-color:rgba(255,255,255,.1);color:#ccc;}.actbrow-widget-empty-title{color:#e5e5e5;}.actbrow-widget-empty-desc{color:#aaa;}.actbrow-widget-suggestion{background:#2a2a2a;color:#e5e5e5;border-color:rgba(255,255,255,.12);}.actbrow-widget-suggestion:hover{background:#303030;border-color:rgba(255,255,255,.18);}.actbrow-widget-suggestion-icon{background:#333;border-color:rgba(255,255,255,.1);}.actbrow-widget-suggestion-icon svg{fill:#e5e5e5;}.actbrow-widget-label{color:#555;}.actbrow-widget-message-assistant{background:#2a2a2a;color:#e5e5e5;border-color:rgba(255,255,255,.08);}.actbrow-widget-message code{background:rgba(255,255,255,.12);}.actbrow-widget-message a{color:#e5e5e5;}.actbrow-widget-message pre{background:#0a0a0a;color:#d4d4d4;border-color:rgba(255,255,255,.08);}.actbrow-widget-thinking-dot{background:#e5e5e5;}.actbrow-widget-message-thinking{background:#2a2a2a;border-color:rgba(255,255,255,.08);}.actbrow-widget-steps{background:#2a2a2a;border-color:rgba(255,255,255,.1);}.actbrow-widget-steps-header{color:#e5e5e5;}.actbrow-widget-steps-list{border-color:rgba(255,255,255,.08);}.actbrow-widget-step-name{color:#e5e5e5;}.actbrow-widget-step-meta{color:#aaa;}.actbrow-widget-status{color:#aaa;}.actbrow-widget-footer{background:#0a0a0a;border-color:rgba(255,255,255,.08);}.actbrow-widget-powered{color:#777;}.actbrow-widget-powered svg{fill:#777;}.actbrow-widget-form{background:#1a1a1a;border-color:rgba(255,255,255,.15);}.actbrow-widget-input{background:#252525;color:#e5e5e5;}.actbrow-widget-input::placeholder{color:#777;}.actbrow-widget-input:focus{background:#2a2a2a;}.actbrow-widget-send{background:#e5e5e5;color:#000;}.actbrow-widget-send:hover{background:#fff;box-shadow:0 3px 10px rgba(255,255,255,.2);}}"
    ].join(""), "actbrow-widget-styles-solid");

    var labels = config.labels || {};
    var suggestions = Array.isArray(config.suggestions)
      ? config.suggestions.filter(function (s) { return typeof s === "string" && s.trim(); })
      : [];
    var emptyTitle = labels.emptyTitle !== undefined ? labels.emptyTitle : "How can I help?";
    var emptyDesc = labels.emptyDesc !== undefined
      ? labels.emptyDesc
      : "Ask a question or describe what you need.";
    var showEmptyState = config.hideEmptyState !== true;
    var root = document.createElement("div");
    root.className = "actbrow-widget-root";

    // Launcher button with icon
    var launcher = document.createElement("button");
    launcher.type = "button";
    launcher.className = "actbrow-widget-launcher";
    launcher.setAttribute("aria-label", labels.open || "Open assistant");
    launcher.setAttribute("aria-expanded", "false");
    launcher.innerHTML = '<div class="actbrow-widget-launcher-icon"><svg viewBox="0 0 24 24"><path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H6l-2 2V4h16v12z"/></svg></div>';

    var panel = document.createElement("section");
    panel.className = "actbrow-widget-panel actbrow-widget-hidden";
    panel.setAttribute("aria-label", labels.title || "ActBrow Assistant");
    panel.setAttribute("role", "dialog");
    panel.setAttribute("aria-modal", "true");

    var resizeHandleTopLeft = document.createElement("div");
    resizeHandleTopLeft.className = "actbrow-widget-resize-handle actbrow-widget-resize-handle-tl";
    resizeHandleTopLeft.setAttribute("aria-label", labels.resizePanel || "Resize chat panel");
    resizeHandleTopLeft.setAttribute("role", "separator");
    panel.appendChild(resizeHandleTopLeft);

    var resizeHandleBottomRight = document.createElement("div");
    resizeHandleBottomRight.className = "actbrow-widget-resize-handle actbrow-widget-resize-handle-br";
    resizeHandleBottomRight.setAttribute("aria-label", labels.resizePanel || "Resize chat panel");
    resizeHandleBottomRight.setAttribute("role", "separator");
    panel.appendChild(resizeHandleBottomRight);

    var header = document.createElement("div");
    header.className = "actbrow-widget-header";
    header.innerHTML = '<div class="actbrow-widget-header-content">' +
      '<div class="actbrow-widget-title"><div class="actbrow-widget-title-icon"><svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm-1-13h2v6h-2zm0 8h2v2h-2z"/></svg></div>' + escapeHtml(labels.title || "ActBrow Assistant") + '</div>' +
      '<div class="actbrow-widget-subtitle">' + escapeHtml(labels.subtitle || "Ask, navigate, and act inside this app") + '</div>' +
      '<div class="actbrow-widget-badge"><span class="actbrow-widget-badge-dot"></span>' +
      '<span class="actbrow-widget-badge-text">' + escapeHtml(labels.badge || "Live in your app") + '</span></div></div>';

    var headerActions = document.createElement("div");
    headerActions.className = "actbrow-widget-header-actions";

    var clearHistoryButton = document.createElement("button");
    clearHistoryButton.type = "button";
    clearHistoryButton.className = "actbrow-widget-clear-history";
    clearHistoryButton.textContent = labels.clearHistory || "New chat";
    clearHistoryButton.setAttribute("aria-label", labels.clearHistoryAria || "Start a new conversation");

    var closeButton = document.createElement("button");
    closeButton.type = "button";
    closeButton.className = "actbrow-widget-close";
    closeButton.setAttribute("aria-label", labels.close || "Close assistant");
    closeButton.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>';
    headerActions.appendChild(clearHistoryButton);
    headerActions.appendChild(closeButton);
    header.appendChild(headerActions);

    var messages = document.createElement("div");
    messages.className = "actbrow-widget-messages";

    var body = document.createElement("div");
    body.className = "actbrow-widget-body";

    var status = document.createElement("div");
    status.className = "actbrow-widget-status";

    var footer = document.createElement("div");
    footer.className = "actbrow-widget-footer";

    var poweredBy = document.createElement("div");
    poweredBy.className = "actbrow-widget-powered";
    poweredBy.innerHTML = '<svg viewBox="0 0 24 24"><path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/></svg>' + escapeHtml(labels.poweredBy || "Powered by ActBrow");

    var formWrap = document.createElement("div");
    formWrap.className = "actbrow-widget-form-wrap";

    var form = document.createElement("form");
    form.className = "actbrow-widget-form";

    var input = document.createElement("textarea");
    input.className = "actbrow-widget-input";
    input.rows = 1;
    input.placeholder = labels.placeholder || "Ask me to navigate or help with what's on this page";
    input.setAttribute("aria-label", "Message input");
    var maxInputHeight = 160;

    function resizeInput() {
      input.style.height = "auto";
      var nextHeight = Math.min(input.scrollHeight, maxInputHeight);
      input.style.height = nextHeight + "px";
    }

    input.addEventListener("input", resizeInput);
    input.addEventListener("paste", function () {
      setTimeout(resizeInput, 0);
    });
    input.addEventListener("keydown", function (event) {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        form.requestSubmit();
      }
    });

    var send = document.createElement("button");
    send.className = "actbrow-widget-send";
    send.type = "submit";
    send.setAttribute("aria-label", "Send message");
    var sendButtonSendHtml = '<svg viewBox="0 0 24 24"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg><span>' + escapeHtml(labels.send || "Send") + '</span>';
    var sendButtonStopHtml = '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2"/></svg><span>' + escapeHtml(labels.stop || "Stop") + '</span>';
    send.innerHTML = sendButtonSendHtml;

    form.appendChild(input);
    form.appendChild(send);
    formWrap.appendChild(form);
    panel.appendChild(header);
    body.appendChild(messages);
    body.appendChild(status);
    footer.appendChild(formWrap);
    footer.appendChild(poweredBy);
    panel.appendChild(body);
    panel.appendChild(footer);
    root.appendChild(launcher);
    root.appendChild(panel);

    (config.mount || document.body).appendChild(root);

    var client = createActbrowClient({
      baseUrl: config.baseUrl,
      assistantId: config.assistantId,
      apiKey: config.apiKey,
      debug: !!config.debug,
      navigate: config.navigate,
      router: config.router,
      routerMethod: config.routerMethod
    });
    global.ActbrowWidgetClient = client;
    // Reconnect any in-progress run from a previous page load or hard refresh
    client.syncRunFromStorage();
    var isOpen = false;
    var isSending = false;
    var thinkingRow = null;
    var stepsState = null;
    var statusMode = "idle";
    var hasSubmittedMessage = false;
    var panelHydrated = false;

    global.__actbrowWidgetElements = { root: root, launcher: launcher, panel: panel };

    function ensureWidgetMounted() {
      var stored = global.__actbrowWidgetElements;
      if (!stored || !stored.root) {
        return;
      }
      if (!document.body.contains(stored.root)) {
        config.mount ? config.mount.appendChild(stored.root) : document.body.appendChild(stored.root);
      }
      if (stored.launcher && !stored.root.contains(stored.launcher)) {
        stored.root.appendChild(stored.launcher);
      }
      if (stored.panel && !stored.root.contains(stored.panel)) {
        stored.root.appendChild(stored.panel);
      }
    }

    if (typeof window !== "undefined") {
      window.addEventListener("popstate", function() {
        setTimeout(function() {
          ensureWidgetMounted();
          // Reconnect any in-progress run SSE stream after SPA navigation
          client.syncRunFromStorage();
        }, 100);
      });
      window.addEventListener("pageshow", function (event) {
        ensureWidgetMounted();
        if (event.persisted) {
          client.syncConversationIdFromStorage();
          client.syncRunFromStorage();
          panelHydrated = false;
        }
      });
      setTimeout(function() {
        ensureWidgetMounted();
      }, 1000);
    }

    function escapeHtml(value) {
      return String(value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
    }

    function setStatus(text) {
      if (text) {
        statusMode = text;
        status.innerHTML = '<span class="actbrow-widget-status-dot"></span>' + escapeHtml(text);
      } else {
        status.textContent = "";
        statusMode = "idle";
      }
    }

    function setSendingState(nextValue) {
      isSending = nextValue;
      input.disabled = nextValue;
      // Keep the send button enabled while sending — it morphs into a Stop button.
      send.disabled = false;
      clearHistoryButton.disabled = nextValue;
      if (nextValue) {
        send.innerHTML = sendButtonStopHtml;
        send.setAttribute("aria-label", "Stop");
        send.title = "Stop";
      } else {
        send.innerHTML = sendButtonSendHtml;
        send.setAttribute("aria-label", "Send message");
        send.title = "";
      }
    }

    function scrollToBottom() {
      messages.scrollTop = messages.scrollHeight;
    }

    function appendRow(role, label) {
      var row = document.createElement("div");
      row.className = "actbrow-widget-row actbrow-widget-row-" + role;
      if (label) {
        var labelNode = document.createElement("div");
        labelNode.className = "actbrow-widget-label";
        labelNode.textContent = label;
        row.appendChild(labelNode);
      }
      messages.appendChild(row);
      scrollToBottom();
      return row;
    }

    function appendMessage(role, content, meta) {
      var row = appendRow(role, role === "user" ? (labels.you || "You") : (labels.assistant || "Assistant"));
      var item = document.createElement("div");
      item.className = "actbrow-widget-message " +
        (role === "user" ? "actbrow-widget-message-user" : "actbrow-widget-message-assistant");
      if (role === "assistant") {
        renderAssistantMessageContent(item, content, meta);
      } else {
        item.textContent = content;
      }
      row.appendChild(item);
      scrollToBottom();
      return row;
    }

    function parseAssistantOptions(content) {
      if (!content) {
        return null;
      }
      var lines = String(content).split(/\r?\n/);
      var optionLineIndex = -1;
      var options = [];
      var recommended = null;
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i].trim();
        if (line.indexOf("OPTIONS:") === 0) {
          optionLineIndex = i;
          options = line.slice("OPTIONS:".length).split("|").map(function (part) {
            return part.trim();
          }).filter(Boolean);
        } else if (line.indexOf("RECOMMENDED:") === 0) {
          recommended = line.slice("RECOMMENDED:".length).trim();
        }
      }
      if (!options.length) {
        return null;
      }
      var visibleLines = lines.filter(function (line, idx) {
        if (idx === optionLineIndex) {
          return false;
        }
        return line.trim().indexOf("RECOMMENDED:") !== 0;
      });
      return {
        text: visibleLines.join("\n").trim(),
        options: options,
        recommended: recommended
      };
    }

    function renderAssistantMessageContent(container, content, meta) {
      var parsed = meta && meta.options && meta.options.length
        ? {
            text: meta.content || content,
            options: meta.options,
            recommended: meta.recommendedOption || null
          }
        : parseAssistantOptions(content);
      if (!parsed || !parsed.options || !parsed.options.length) {
        container.textContent = content;
        return;
      }
      var body = document.createElement("div");
      body.className = "actbrow-widget-message-body";
      body.textContent = parsed.text || content;
      container.appendChild(body);

      var options = document.createElement("div");
      options.className = "actbrow-widget-message-options";
      parsed.options.forEach(function (optionText) {
        var button = document.createElement("button");
        button.type = "button";
        button.className = "actbrow-widget-message-option";
        if (parsed.recommended && optionText.toLowerCase() === parsed.recommended.toLowerCase()) {
          button.className += " actbrow-widget-message-option-recommended";
        }
        button.textContent = optionText;
        button.addEventListener("click", function () {
          if (button.disabled) {
            return;
          }
          var siblings = options.querySelectorAll(".actbrow-widget-message-option");
          siblings.forEach(function (node) {
            node.disabled = true;
            node.style.opacity = "0.55";
            node.style.cursor = "default";
          });
          openPanel();
          submitPrompt(optionText);
        });
        options.appendChild(button);
      });
      container.appendChild(options);
    }

    var STEP_ICON_RUNNING = '<svg viewBox="0 0 16 16"><path d="M8 1.5a6.5 6.5 0 1 0 6.5 6.5h-2A4.5 4.5 0 1 1 8 3.5z"/></svg>';
    var STEP_ICON_DONE = '<svg viewBox="0 0 16 16"><path d="M13.78 4.22a.75.75 0 0 1 0 1.06l-7 7a.75.75 0 0 1-1.06 0l-3.5-3.5a.75.75 0 1 1 1.06-1.06L6.25 10.69l6.47-6.47a.75.75 0 0 1 1.06 0z"/></svg>';
    var STEP_ICON_ERROR = '<svg viewBox="0 0 16 16"><path d="M4.22 4.22a.75.75 0 0 1 1.06 0L8 6.94l2.72-2.72a.75.75 0 1 1 1.06 1.06L9.06 8l2.72 2.72a.75.75 0 1 1-1.06 1.06L8 9.06l-2.72 2.72a.75.75 0 1 1-1.06-1.06L6.94 8 4.22 5.28a.75.75 0 0 1 0-1.06z"/></svg>';
    var STEPS_CHEVRON = '<svg viewBox="0 0 16 16"><path d="M3.22 5.78a.75.75 0 0 1 1.06 0L8 9.5l3.72-3.72a.75.75 0 1 1 1.06 1.06l-4.25 4.25a.75.75 0 0 1-1.06 0L3.22 6.84a.75.75 0 0 1 0-1.06z"/></svg>';
    var STEPS_ICON_DONE = '<svg viewBox="0 0 16 16"><path d="M2 4a1 1 0 0 1 1-1h10a1 1 0 1 1 0 2H3a1 1 0 0 1-1-1zm0 4a1 1 0 0 1 1-1h10a1 1 0 1 1 0 2H3a1 1 0 0 1-1-1zm1 3a1 1 0 1 0 0 2h10a1 1 0 1 0 0-2z"/></svg>';

    function truncateText(value, max) {
      var s = String(value == null ? "" : value);
      return s.length > max ? s.slice(0, max - 1) + "…" : s;
    }

    function formatStepArgs(args) {
      if (!args) return "";
      try {
        return truncateText(JSON.stringify(args), 180);
      } catch (e) {
        return "";
      }
    }

    function ensureStepsRow() {
      if (stepsState) return stepsState;
      var row = appendRow("assistant", labels.activity || "Steps");
      var box = document.createElement("div");
      box.className = "actbrow-widget-steps is-open is-running";
      var header = document.createElement("button");
      header.type = "button";
      header.className = "actbrow-widget-steps-header";
      header.setAttribute("aria-expanded", "true");
      var icon = document.createElement("span");
      icon.className = "actbrow-widget-steps-icon";
      icon.innerHTML = STEP_ICON_RUNNING;
      var title = document.createElement("span");
      title.className = "actbrow-widget-steps-title";
      title.textContent = "Working…";
      var chevron = document.createElement("span");
      chevron.className = "actbrow-widget-steps-chevron";
      chevron.innerHTML = STEPS_CHEVRON;
      header.appendChild(icon);
      header.appendChild(title);
      header.appendChild(chevron);
      var list = document.createElement("div");
      list.className = "actbrow-widget-steps-list";
      box.appendChild(header);
      box.appendChild(list);
      row.appendChild(box);
      header.addEventListener("click", function () {
        var nowOpen = box.classList.toggle("is-open");
        header.setAttribute("aria-expanded", nowOpen ? "true" : "false");
      });
      stepsState = {
        row: row,
        box: box,
        list: list,
        header: header,
        headerIcon: icon,
        headerTitle: title,
        items: {},
        order: [],
        count: 0,
        failed: 0
      };
      scrollToBottom();
      return stepsState;
    }

    function addStepRequested(payload) {
      var state = ensureStepsRow();
      var toolCallId = payload.toolCallId || ("step-" + state.count);
      state.count += 1;
      var item = document.createElement("div");
      item.className = "actbrow-widget-step is-running";
      var statusEl = document.createElement("span");
      statusEl.className = "actbrow-widget-step-status";
      statusEl.innerHTML = STEP_ICON_RUNNING;
      var bodyEl = document.createElement("div");
      bodyEl.className = "actbrow-widget-step-body";
      var nameEl = document.createElement("div");
      nameEl.className = "actbrow-widget-step-name";
      nameEl.textContent = payload.toolKey || payload.executorKey || "tool";
      var metaEl = document.createElement("div");
      metaEl.className = "actbrow-widget-step-meta";
      var argsText = formatStepArgs(payload.arguments);
      metaEl.textContent = argsText || "Running…";
      bodyEl.appendChild(nameEl);
      bodyEl.appendChild(metaEl);
      item.appendChild(statusEl);
      item.appendChild(bodyEl);
      state.list.appendChild(item);
      state.items[toolCallId] = { item: item, statusEl: statusEl, metaEl: metaEl, args: argsText };
      state.order.push(toolCallId);
      state.headerTitle.textContent = "Running " + state.count + " step" + (state.count === 1 ? "" : "s") + "…";
      scrollToBottom();
    }

    function markStepCompleted(payload) {
      if (!stepsState) return;
      var entry = stepsState.items[payload.toolCallId];
      if (!entry) return;
      var success = payload.success !== false;
      entry.item.classList.remove("is-running");
      entry.item.classList.add(success ? "is-done" : "is-error");
      entry.statusEl.innerHTML = success ? STEP_ICON_DONE : STEP_ICON_ERROR;
      var summary = payload.textSummary || payload.error || (success ? "Completed" : "Failed");
      entry.metaEl.textContent = truncateText(summary, 240);
      if (!success) stepsState.failed += 1;
    }

    function finalizeStepsRow(finalState) {
      if (!stepsState) return;
      var state = stepsState;
      stepsState = null;
      state.order.forEach(function (id) {
        var entry = state.items[id];
        if (entry.item.classList.contains("is-running")) {
          entry.item.classList.remove("is-running");
          entry.item.classList.add("is-error");
          entry.statusEl.innerHTML = STEP_ICON_ERROR;
          entry.metaEl.textContent = finalState === "failed" ? "Did not complete" : "Stopped";
          state.failed += 1;
        }
      });
      var n = state.count;
      if (n === 0) {
        if (state.row.parentNode) state.row.parentNode.removeChild(state.row);
        return;
      }
      var label;
      if (finalState === "failed") {
        label = "Stopped after " + n + " step" + (n === 1 ? "" : "s");
      } else if (finalState === "cancelled") {
        label = "Cancelled after " + n + " step" + (n === 1 ? "" : "s");
      } else if (state.failed > 0) {
        label = "Used " + n + " step" + (n === 1 ? "" : "s") + " · " + state.failed + " failed";
      } else {
        label = "Used " + n + " step" + (n === 1 ? "" : "s");
      }
      state.headerTitle.textContent = label;
      state.box.classList.remove("is-running");
      state.box.classList.remove("is-open");
      state.header.setAttribute("aria-expanded", "false");
      var bad = state.failed > 0 || finalState === "failed" || finalState === "cancelled";
      if (bad) {
        state.box.classList.add("is-error");
        state.headerIcon.innerHTML = STEP_ICON_ERROR;
      } else {
        state.headerIcon.innerHTML = STEPS_ICON_DONE;
      }
    }

    function removeThinkingRow() {
      if (thinkingRow && thinkingRow.parentNode) {
        thinkingRow.parentNode.removeChild(thinkingRow);
      }
      thinkingRow = null;
    }

    function ensureThinkingRow() {
      if (thinkingRow) {
        return;
      }
      thinkingRow = appendRow("assistant", labels.assistant || "Assistant");
      var item = document.createElement("div");
      item.className = "actbrow-widget-message actbrow-widget-message-assistant actbrow-widget-message-thinking";
      item.innerHTML =
        '<span class="actbrow-widget-thinking-dot"></span>' +
        '<span class="actbrow-widget-thinking-dot"></span>' +
        '<span class="actbrow-widget-thinking-dot"></span>';
      thinkingRow.appendChild(item);
      scrollToBottom();
    }

    function submitPrompt(text) {
      hasSubmittedMessage = true;
      if (showEmptyState) {
        removeEmptyState();
      }
      appendMessage("user", text);
      input.value = "";
      input.style.height = "auto";
      resizeInput();
      setSendingState(true);
      ensureThinkingRow();
      setStatus(labels.statusThinking || "Thinking...");
      client.ensureConversation()
        .then(function () {
          return client.sendMessage({ content: text });
        })
        .catch(function (error) {
          removeThinkingRow();
          appendMessage("assistant", "Request failed: " + error.message);
          setStatus("");
          setSendingState(false);
        });
    }

    function renderEmptyState() {
      if (!showEmptyState || document.getElementById("actbrow-widget-empty-state")) {
        return;
      }
      var parts = [];
      if (emptyTitle) {
        parts.push('<div class="actbrow-widget-empty-title">' + escapeHtml(emptyTitle) + '</div>');
      }
      if (emptyDesc) {
        parts.push('<div class="actbrow-widget-empty-desc">' + escapeHtml(emptyDesc) + '</div>');
      }
      if (suggestions.length) {
        parts.push('<div class="actbrow-widget-suggestions"></div>');
      }
      if (!parts.length) {
        return;
      }
      var item = document.createElement("div");
      item.id = "actbrow-widget-empty-state";
      item.className = "actbrow-widget-empty";
      item.innerHTML = parts.join("");
      messages.appendChild(item);
      var suggestionContainer = item.querySelector(".actbrow-widget-suggestions");
      if (!suggestionContainer) {
        return;
      }
      var suggestionIcons = [
        '<svg viewBox="0 0 24 24"><path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"/></svg>',
        '<svg viewBox="0 0 24 24"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg>',
        '<svg viewBox="0 0 24 24"><path d="M19.14 12.94c.04-.31.06-.63.06-.94 0-.31-.02-.63-.06-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L5.09 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.04.31-.06.63-.06.94s.02.63.06.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>',
        '<svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>'
      ];
      suggestions.slice(0, 4).forEach(function (suggestion, idx) {
        var button = document.createElement("button");
        button.type = "button";
        button.className = "actbrow-widget-suggestion";
        button.innerHTML = '<div class="actbrow-widget-suggestion-icon">' + suggestionIcons[idx % suggestionIcons.length] + '</div>' + escapeHtml(suggestion);
        button.addEventListener("click", function () {
          openPanel();
          submitPrompt(suggestion);
        });
        suggestionContainer.appendChild(button);
      });
    }

    function removeEmptyState() {
      var existing = document.getElementById("actbrow-widget-empty-state");
      if (existing && existing.parentNode) {
        existing.parentNode.removeChild(existing);
      }
    }

    function addWelcomeIfNeeded() {
      if (!messages.childElementCount && (labels.welcomeTitle || labels.welcome)) {
        var welcomeRow = appendRow("assistant", labels.assistant || "Assistant");
        var welcomeItem = document.createElement("div");
        welcomeItem.className = "actbrow-widget-message actbrow-widget-message-assistant";
        var welcomeHtml = "";
        if (labels.welcomeTitle) {
          welcomeHtml += "<strong>" + escapeHtml(labels.welcomeTitle) + "</strong>";
        }
        if (labels.welcome) {
          welcomeHtml += (welcomeHtml ? "<br/>" : "") + escapeHtml(labels.welcome);
        }
        welcomeItem.innerHTML = welcomeHtml;
        welcomeRow.appendChild(welcomeItem);
      }
    }

    function beginNewConversation() {
      removeThinkingRow();
      thinkingRow = null;
      stepsState = null;
      while (messages.firstChild) {
        messages.removeChild(messages.firstChild);
      }
      hasSubmittedMessage = false;
      panelHydrated = false;
      setStatus("");
      clearHistoryButton.disabled = true;
      input.disabled = true;
      send.disabled = true;
      client.resetConversationForNewChat().then(function () {
        setSendingState(false);
        if (isOpen) {
          addWelcomeIfNeeded();
          if (showEmptyState) {
            renderEmptyState();
          }
          scrollToBottom();
        }
      });
    }

    function openPanel() {
      isOpen = true;
      panel.classList.remove("actbrow-widget-hidden");
      launcher.setAttribute("aria-expanded", "true");
      client.syncConversationIdFromStorage();

      function finishOpen() {
        if (!hasSubmittedMessage) {
          if (showEmptyState) {
            renderEmptyState();
          }
        } else {
          removeEmptyState();
        }
        input.focus();
      }

      if (!panelHydrated) {
        panelHydrated = true;
        while (messages.firstChild) {
          messages.removeChild(messages.firstChild);
        }
        var cid = client.getConversationId();
        if (cid) {
          client
            .listMessages(cid)
            .then(function (rows) {
              if (rows && rows.length) {
                hasSubmittedMessage = true;
                rows.forEach(function (m) {
                  var role = (m.role || "").toUpperCase();
                  if (role === "USER") {
                    appendMessage("user", m.content);
                  } else if (role === "ASSISTANT") {
                    appendMessage("assistant", m.content);
                  }
                });
                scrollToBottom();
              } else {
                addWelcomeIfNeeded();
              }
              finishOpen();
            })
            .catch(function () {
              client.clearStoredConversation();
              addWelcomeIfNeeded();
              finishOpen();
            });
          return;
        }
      }

      if (!messages.childElementCount) {
        addWelcomeIfNeeded();
      }
      finishOpen();
    }

    function closePanel() {
      isOpen = false;
      panel.classList.add("actbrow-widget-hidden");
      launcher.setAttribute("aria-expanded", "false");
      launcher.focus();
    }

    var panelLayoutStorageKey = "actbrow-widget-layout-" + (config.assistantId || "default");
    var panelLegacySizeStorageKey = "actbrow-widget-size-" + (config.assistantId || "default");
    var panelMargin = 12;
    var launcherGap = 88;
    var maxPanelWidth = 480;
    var maxPanelHeight = 640;

    function clampPanelSize(width, height) {
      var minW = 280;
      var minH = 320;
      var maxW = Math.min(maxPanelWidth, Math.max(minW, window.innerWidth - panelMargin * 2));
      var maxH = Math.min(maxPanelHeight, Math.max(minH, window.innerHeight - panelMargin * 2));
      return {
        width: Math.min(maxW, Math.max(minW, width)),
        height: Math.min(maxH, Math.max(minH, height))
      };
    }

    function clampPanelPosition(left, top, width, height) {
      var maxLeft = Math.max(panelMargin, window.innerWidth - width - panelMargin);
      var maxTop = Math.max(panelMargin, window.innerHeight - height - panelMargin);
      return {
        left: Math.min(maxLeft, Math.max(panelMargin, left)),
        top: Math.min(maxTop, Math.max(panelMargin, top))
      };
    }

    function defaultPanelLayout() {
      var size = clampPanelSize(340, 480);
      var left = window.innerWidth - size.width - 24;
      var top = window.innerHeight - size.height - launcherGap - 24;
      return {
        left: left,
        top: top,
        width: size.width,
        height: size.height
      };
    }

    function applyPanelLayout(layout) {
      var size = clampPanelSize(layout.width, layout.height);
      var pos = clampPanelPosition(layout.left, layout.top, size.width, size.height);
      panel.style.width = size.width + "px";
      panel.style.height = size.height + "px";
      panel.style.left = pos.left + "px";
      panel.style.top = pos.top + "px";
      panel.style.right = "auto";
      panel.style.bottom = "auto";
      return {
        left: pos.left,
        top: pos.top,
        width: size.width,
        height: size.height
      };
    }

    function readPanelLayout() {
      var left = parseFloat(panel.style.left);
      var top = parseFloat(panel.style.top);
      if (isNaN(left) || isNaN(top)) {
        return defaultPanelLayout();
      }
      return {
        left: left,
        top: top,
        width: panel.offsetWidth,
        height: panel.offsetHeight
      };
    }

    function savePanelLayout() {
      try {
        var layout = applyPanelLayout(readPanelLayout());
        global.localStorage.setItem(panelLayoutStorageKey, JSON.stringify(layout));
      } catch (e) {
        // ignore storage failures
      }
    }

    function loadPanelLayout() {
      try {
        var raw = global.localStorage.getItem(panelLayoutStorageKey);
        if (raw) {
          var parsed = JSON.parse(raw);
          if (parsed && typeof parsed.width === "number" && typeof parsed.height === "number") {
            return applyPanelLayout({
              left: typeof parsed.left === "number" ? parsed.left : defaultPanelLayout().left,
              top: typeof parsed.top === "number" ? parsed.top : defaultPanelLayout().top,
              width: parsed.width,
              height: parsed.height
            });
          }
        }
        var legacyRaw = global.localStorage.getItem(panelLegacySizeStorageKey);
        if (legacyRaw) {
          var legacy = JSON.parse(legacyRaw);
          if (legacy && typeof legacy.width === "number" && typeof legacy.height === "number") {
            var defaults = defaultPanelLayout();
            return applyPanelLayout({
              left: defaults.left,
              top: defaults.top,
              width: legacy.width,
              height: legacy.height
            });
          }
        }
      } catch (e) {
        // fall through to defaults
      }
      return applyPanelLayout(defaultPanelLayout());
    }

    function bindPointerDrag(startEvent, onMove, onEnd) {
      startEvent.preventDefault();
      function onPointerMove(moveEvent) {
        onMove(moveEvent);
      }
      function onPointerUp() {
        document.removeEventListener("mousemove", onPointerMove);
        document.removeEventListener("mouseup", onPointerUp);
        document.removeEventListener("touchmove", onPointerMove);
        document.removeEventListener("touchend", onPointerUp);
        document.removeEventListener("touchcancel", onPointerUp);
        if (typeof onEnd === "function") {
          onEnd();
        }
      }
      document.addEventListener("mousemove", onPointerMove);
      document.addEventListener("mouseup", onPointerUp);
      document.addEventListener("touchmove", onPointerMove, { passive: false });
      document.addEventListener("touchend", onPointerUp);
      document.addEventListener("touchcancel", onPointerUp);
    }

    function pointerClientXY(event) {
      if (event.touches && event.touches.length) {
        return { x: event.touches[0].clientX, y: event.touches[0].clientY };
      }
      return { x: event.clientX, y: event.clientY };
    }

    function startPanelDrag(startEvent) {
      if (startEvent.button != null && startEvent.button !== 0) {
        return;
      }
      if (startEvent.target && startEvent.target.closest && startEvent.target.closest("button")) {
        return;
      }
      panel.classList.add("actbrow-widget-dragging");
      var rect = panel.getBoundingClientRect();
      var start = pointerClientXY(startEvent);
      var offsetX = start.x - rect.left;
      var offsetY = start.y - rect.top;
      bindPointerDrag(startEvent, function (moveEvent) {
        var point = pointerClientXY(moveEvent);
        applyPanelLayout({
          left: point.x - offsetX,
          top: point.y - offsetY,
          width: panel.offsetWidth,
          height: panel.offsetHeight
        });
      }, function () {
        panel.classList.remove("actbrow-widget-dragging");
        savePanelLayout();
      });
    }

    function startPanelResize(startEvent, corner) {
      if (startEvent.button != null && startEvent.button !== 0) {
        return;
      }
      panel.classList.add("actbrow-widget-resizing");
      var start = pointerClientXY(startEvent);
      var layout = readPanelLayout();
      var startLeft = layout.left;
      var startTop = layout.top;
      var startWidth = layout.width;
      var startHeight = layout.height;
      bindPointerDrag(startEvent, function (moveEvent) {
        var point = pointerClientXY(moveEvent);
        if (corner === "tl") {
          var nextWidth = startWidth + (start.x - point.x);
          var nextHeight = startHeight + (start.y - point.y);
          var size = clampPanelSize(nextWidth, nextHeight);
          applyPanelLayout({
            left: startLeft + (startWidth - size.width),
            top: startTop + (startHeight - size.height),
            width: size.width,
            height: size.height
          });
        } else {
          applyPanelLayout({
            left: startLeft,
            top: startTop,
            width: startWidth + (point.x - start.x),
            height: startHeight + (point.y - start.y)
          });
        }
      }, function () {
        panel.classList.remove("actbrow-widget-resizing");
        savePanelLayout();
      });
    }

    loadPanelLayout();

    header.addEventListener("mousedown", startPanelDrag);
    header.addEventListener("touchstart", startPanelDrag, { passive: false });

    resizeHandleTopLeft.addEventListener("mousedown", function (event) {
      startPanelResize(event, "tl");
    });
    resizeHandleTopLeft.addEventListener("touchstart", function (event) {
      startPanelResize(event, "tl");
    }, { passive: false });

    resizeHandleBottomRight.addEventListener("mousedown", function (event) {
      startPanelResize(event, "br");
    });
    resizeHandleBottomRight.addEventListener("touchstart", function (event) {
      startPanelResize(event, "br");
    }, { passive: false });

    window.addEventListener("resize", function () {
      applyPanelLayout(readPanelLayout());
    });

    resizeInput();

    // Keyboard accessibility - Escape to close
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && isOpen) {
        closePanel();
      }
    });

    launcher.addEventListener("click", function () {
      if (isOpen) {
        closePanel();
      } else {
        openPanel();
      }
    });

    closeButton.addEventListener("click", function () {
      closePanel();
    });

    clearHistoryButton.addEventListener("click", function () {
      beginNewConversation();
    });

    client.on("tool.call.requested", function (event) {
      if (event && event.payload) {
        removeThinkingRow();
        setStatus("Running " + event.payload.toolKey + "...");
        addStepRequested(event.payload);
      }
    });

    client.on("tool.call.completed", function (event) {
      if (event && event.payload) {
        markStepCompleted(event.payload);
      }
      setStatus("Tool completed.");
    });

    client.on("assistant.message.completed", function (event) {
      removeThinkingRow();
      finalizeStepsRow("completed");
      appendMessage("assistant", event.payload.content, event.payload);
      setStatus("");
      setSendingState(false);
    });

    client.on("run.failed", function (event) {
      removeThinkingRow();
      finalizeStepsRow("failed");
      appendMessage("assistant", "Request failed: " + event.payload.message);
      setStatus("");
      setSendingState(false);
    });

    client.on("run.cancelled", function () {
      removeThinkingRow();
      finalizeStepsRow("cancelled");
      appendMessage("assistant", labels.cancelledMessage || "Stopped.");
      setStatus("");
      setSendingState(false);
    });

    form.addEventListener("submit", function (submitEvent) {
      submitEvent.preventDefault();
      if (isSending) {
        client.cancelRun();
        return;
      }
      var text = input.value.trim();
      if (!text) {
        return;
      }
      submitPrompt(text);
    });

    return {
      client: client,
      open: openPanel,
      close: closePanel,
      beginNewConversation: beginNewConversation,
      destroy: function () {
        root.remove();
      }
    };
  }

  global.Actbrow = {
    SDK_VERSION: "6",
    createActbrowClient: createActbrowClient,
    createActbrowWidget: createActbrowWidget
  };
})(window);
