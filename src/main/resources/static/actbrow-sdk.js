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

  function readElement(selector) {
    var element = document.querySelector(selector);
    if (!element) {
      throw new Error("Element not found: " + selector);
    }
    return element;
  }

  function builtInTools(config) {
    return {
      "dom.query": function (args) {
        var nodes = Array.prototype.slice.call(document.querySelectorAll(args.selector || ""));
        return {
          success: true,
          structuredOutput: JSON.stringify({
            selector: args.selector,
            count: nodes.length,
            matches: nodes.map(function (node) {
              return {
                text: (node.innerText || "").slice(0, 200),
                tagName: node.tagName
              };
            })
          }),
          textSummary: "Matched " + nodes.length + " nodes"
        };
      },
      "dom.click": function (args) {
        readElement(args.selector).click();
        return {
          success: true,
          structuredOutput: JSON.stringify({ selector: args.selector }),
          textSummary: "Clicked " + args.selector
        };
      },
      "dom.type": function (args) {
        var element = readElement(args.selector);
        element.focus();
        element.value = args.value || "";
        element.dispatchEvent(new Event("input", { bubbles: true }));
        element.dispatchEvent(new Event("change", { bubbles: true }));
        return {
          success: true,
          structuredOutput: JSON.stringify({ selector: args.selector, value: args.value || "" }),
          textSummary: "Typed into " + args.selector
        };
      },
      "dom.read": function (args) {
        var element = readElement(args.selector);
        return {
          success: true,
          structuredOutput: JSON.stringify({
            selector: args.selector,
            text: element.innerText || "",
            value: element.value || null
          }),
          textSummary: "Read " + args.selector
        };
      },
      "app.navigate": function (args) {
        debugLog(config, "app.navigate invoked", args);
        if (args.path) {
          window.location.assign(args.path);
        } else if (args.url) {
          window.location.assign(args.url);
        }
        return {
          success: true,
          structuredOutput: JSON.stringify({ path: args.path || null, url: args.url || null }),
          textSummary: "Navigation requested"
        };
      },
      "page.screenshot": function () {
        return {
          success: true,
          structuredOutput: JSON.stringify({
            title: document.title,
            html: document.documentElement.outerHTML.slice(0, 5000)
          }),
          textSummary: "Captured DOM snapshot"
        };
      }
    };
  }

  function createActbrowClient(config) {
    if (!config || !config.baseUrl || !config.assistantId) {
      throw new Error("baseUrl and assistantId are required");
    }

    var emitter = createEmitter();
    var tools = builtInTools(config);
    var currentConversationId = null;

    function request(path, options) {
      debugLog(config, "request", path, options && options.method ? options.method : "GET");
      return fetch(config.baseUrl + path, options).then(function (response) {
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

    function postToolResult(runId, body) {
      return request("/v1/runs/" + runId + "/tool-results", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
      });
    }

    function streamRun(runId) {
      var source = new EventSource(config.baseUrl + "/v1/runs/" + runId + "/events");
      debugLog(config, "opening run stream", runId);
      source.onopen = function () {
        debugLog(config, "run stream open", runId);
      };
      ["run.started", "tool.call.requested", "tool.call.completed", "assistant.message.completed", "run.failed"]
        .forEach(function (eventName) {
          source.addEventListener(eventName, function (event) {
            handleRunEvent(runId, event);
            if (eventName === "assistant.message.completed" || eventName === "run.failed") {
              source.close();
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
          return conversation;
        });
      },
      sendMessage: function (message) {
        var ensureConversation = currentConversationId
          ? Promise.resolve({ id: currentConversationId })
          : this.startConversation();
        return ensureConversation.then(function (conversation) {
          return request("/v1/conversations/" + conversation.id + "/turns", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ content: message.content })
          }).then(function (run) {
            streamRun(run.id);
            return run;
          });
        });
      },
      ensureConversation: function () {
        return currentConversationId
          ? Promise.resolve({ id: currentConversationId })
          : this.startConversation();
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
      ".actbrow-widget-root{position:fixed;right:24px;bottom:24px;z-index:2147483000;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;-webkit-font-smoothing:antialiased;-moz-osx-font-smoothing:grayscale;}",
      
      "/* Launcher Button - Solid Color */",
      ".actbrow-widget-launcher{position:relative;width:56px;height:56px;border:none;border-radius:999px;background:#1a1a1a;color:#fff;font-weight:600;font-size:13px;letter-spacing:.02em;cursor:pointer;box-shadow:0 8px 32px rgba(0,0,0,.2);transition:all .3s cubic-bezier(.4,0,.2,1);outline:none;overflow:hidden;border:1px solid rgba(0,0,0,.15);}",
      ".actbrow-widget-launcher:hover{transform:translateY(-3px) scale(1.02);background:#222;box-shadow:0 16px 48px rgba(0,0,0,.25);}",
      ".actbrow-widget-launcher:active{transform:translateY(-1px) scale(.98);box-shadow:0 8px 24px rgba(0,0,0,.2);}",
      ".actbrow-widget-launcher:focus-visible{box-shadow:0 0 0 4px rgba(0,0,0,.15),0 8px 32px rgba(0,0,0,.2);}",
      ".actbrow-widget-launcher::before{content:'';position:absolute;inset:0;border-radius:inherit;background:linear-gradient(135deg,rgba(255,255,255,.15) 0%,rgba(255,255,255,.05) 100%);pointer-events:none;}",
      ".actbrow-widget-launcher::after{content:'';position:absolute;inset:-3px;border-radius:inherit;border:1px solid rgba(255,255,255,.15);animation:actbrow-launcher-pulse 2s ease-out infinite;pointer-events:none;}",
      ".actbrow-widget-launcher-icon{display:flex;align-items:center;justify-content:center;width:100%;height:100%;position:relative;z-index:1;}",
      ".actbrow-widget-launcher-icon svg{width:24px;height:24px;fill:currentColor;filter:drop-shadow(0 2px 4px rgba(0,0,0,.2));}",
      
      "/* Chat Panel - Dark Theme matching actbrow UI */",
      ".actbrow-widget-panel{position:absolute;right:0;bottom:88px;width:340px;max-width:calc(100vw - 48px);height:480px;max-height:calc(100vh - 120px);display:flex;flex-direction:column;border-radius:20px;overflow:hidden;background:linear-gradient(180deg,#1a1a2e 0%,#0f0f1a 100%);border:1px solid rgba(255,255,255,.1);box-shadow:0 32px 96px rgba(0,0,0,.4),0 8px 24px rgba(0,0,0,.2);transition:all .3s cubic-bezier(.4,0,.2,1);transform-origin:bottom right;}",
      ".actbrow-widget-panel.actbrow-widget-hidden{opacity:0;transform:scale(.92) translateY(12px);pointer-events:none;}",
      ".actbrow-widget-panel:not(.actbrow-widget-hidden){opacity:1;transform:scale(1) translateY(0);}",

      "/* Header - Dark gradient */",
      ".actbrow-widget-header{padding:14px 14px 12px;background:linear-gradient(180deg,rgba(255,255,255,.08) 0%,transparent 100%);color:#e5e5e5;display:flex;justify-content:space-between;align-items:flex-start;gap:10px;border-bottom:1px solid rgba(255,255,255,.08);position:relative;}",
      ".actbrow-widget-header::after{content:'';position:absolute;bottom:0;left:0;right:0;height:1px;background:linear-gradient(90deg,transparent 0%,rgba(255,255,255,.06) 50%,transparent 100%);}",
      ".actbrow-widget-header-content{flex:1;min-width:0;}",
      ".actbrow-widget-title{font-size:13px;font-weight:700;letter-spacing:-.02em;color:#e5e5e5;margin-bottom:3px;display:flex;align-items:center;gap:7px;font-family:'Inter',sans-serif;}",
      ".actbrow-widget-title-icon{width:18px;height:18px;background:linear-gradient(135deg,#ffffff 0%,#a0a0a0 100%);border-radius:5px;display:flex;align-items:center;justify-content:center;box-shadow:0 2px 6px rgba(0,0,0,.3);}",
      ".actbrow-widget-title-icon svg{width:11px;height:11px;fill:#000;}",
      ".actbrow-widget-subtitle{font-size:11px;color:#a0a0a0;line-height:1.4;max-width:220px;font-weight:400;}",
      ".actbrow-widget-badge{display:inline-flex;align-items:center;gap:5px;margin-top:8px;padding:3px 8px;background:rgba(34,197,94,.15);border-radius:999px;border:1px solid rgba(34,197,94,.3);}",
      ".actbrow-widget-badge-dot{width:6px;height:6px;border-radius:999px;background:#22c55e;box-shadow:0 0 0 3px rgba(34,197,94,.2);animation:actbrow-badge-pulse 2s ease-in-out infinite;}",
      ".actbrow-widget-badge-text{font-size:9px;font-weight:600;color:#4ade80;letter-spacing:.02em;}",
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

      "/* Tool Activity Card */",
      ".actbrow-widget-tool{max-width:90%;padding:10px 12px;border-radius:14px;background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.1);color:#e5e5e5;box-shadow:0 2px 6px rgba(0,0,0,.2);}",
      ".actbrow-widget-tool-title{font-size:11px;font-weight:700;color:#e5e5e5;display:flex;align-items:center;gap:6px;margin-bottom:5px;font-family:'Inter',sans-serif;}",
      ".actbrow-widget-tool-icon{width:16px;height:16px;background:rgba(255,255,255,.1);border-radius:5px;display:flex;align-items:center;justify-content:center;border:1px solid rgba(255,255,255,.1);}",
      ".actbrow-widget-tool-icon svg{width:10px;height:10px;fill:#e5e5e5;}",
      ".actbrow-widget-tool-meta{font-size:10px;color:#a0a0a0;line-height:1.4;background:rgba(0,0,0,.3);padding:6px 8px;border-radius:6px;font-family:'JetBrains Mono',monospace;overflow-x:auto;border:1px solid rgba(255,255,255,.06);}",
      
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
      ".actbrow-widget-form{display:flex;gap:6px;padding:5px;border:1px solid rgba(255,255,255,.15);border-radius:16px;background:#2a2a2a;box-shadow:0 2px 6px rgba(0,0,0,.2);transition:all .2s ease;}",
      ".actbrow-widget-form:focus-within{border-color:rgba(255,255,255,.3);box-shadow:0 3px 12px rgba(0,0,0,.3),0 0 0 3px rgba(255,255,255,.08);}",
      ".actbrow-widget-input{flex:1;border:none;background:#3a3a3a;padding:9px 12px;font-size:13px;color:#fff;outline:none;font-family:'Inter',sans-serif;border-radius:12px;}",
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
      "@media (max-width:640px){.actbrow-widget-root{right:12px;left:12px;bottom:12px;}.actbrow-widget-panel{width:calc(100% - 24px) !important;left:12px !important;right:12px !important;bottom:80px;height:65vh;max-height:calc(100vh - 100px);border-radius:16px;}.actbrow-widget-launcher{width:52px;height:52px;margin-left:auto;display:block;}.actbrow-widget-message,.actbrow-widget-tool{max-width:90%;}}",
      
      "/* Reduced Motion */",
      "@media (prefers-reduced-motion:reduce){.actbrow-widget-launcher,.actbrow-widget-panel,.actbrow-widget-row,.actbrow-widget-close,.actbrow-widget-suggestion,.actbrow-widget-send{transition:none;animation:none;}.actbrow-widget-thinking-dot{animation:none;}}",
      
      "/* Dark Mode Support */",
      "@media (prefers-color-scheme:dark){.actbrow-widget-launcher{background:#fff;color:#000;box-shadow:0 8px 32px rgba(255,255,255,.15);}.actbrow-widget-launcher:hover{background:#f5f5f5;}.actbrow-widget-launcher::before{background:linear-gradient(135deg,rgba(0,0,0,.1) 0%,rgba(0,0,0,.02) 100%);}.actbrow-widget-launcher::after{border-color:rgba(0,0,0,.15);}.actbrow-widget-panel{background:#1e1e1e;border-color:rgba(255,255,255,.08);box-shadow:0 32px 96px rgba(0,0,0,.4),0 8px 24px rgba(0,0,0,.2);}.actbrow-widget-header{background:#252525;color:#e5e5e5;border-color:rgba(255,255,255,.08);}.actbrow-widget-header::after{background:linear-gradient(90deg,transparent 0%,rgba(255,255,255,.06) 50%,transparent 100%);}.actbrow-widget-title{color:#e5e5e5;}.actbrow-widget-title-icon{background:#e5e5e5;}.actbrow-widget-title-icon svg{fill:#000;}.actbrow-widget-subtitle{color:#aaa;}.actbrow-widget-badge{background:rgba(34,197,94,.2);border-color:rgba(34,197,94,.3);}.actbrow-widget-badge-text{color:#4ade80;}.actbrow-widget-close{background:rgba(255,255,255,.08);color:#aaa;}.actbrow-widget-close:hover{background:rgba(255,255,255,.12);color:#e5e5e5;}.actbrow-widget-messages::-webkit-scrollbar-thumb{background:rgba(255,255,255,.2);}.actbrow-widget-messages::-webkit-scrollbar-thumb:hover{background:rgba(255,255,255,.3);}.actbrow-widget-empty{background:#2a2a2a;border-color:rgba(255,255,255,.1);color:#ccc;}.actbrow-widget-empty-title{color:#e5e5e5;}.actbrow-widget-empty-desc{color:#aaa;}.actbrow-widget-suggestion{background:#2a2a2a;color:#e5e5e5;border-color:rgba(255,255,255,.12);}.actbrow-widget-suggestion:hover{background:#303030;border-color:rgba(255,255,255,.18);}.actbrow-widget-suggestion-icon{background:#333;border-color:rgba(255,255,255,.1);}.actbrow-widget-suggestion-icon svg{fill:#e5e5e5;}.actbrow-widget-label{color:#555;}.actbrow-widget-message-assistant{background:#2a2a2a;color:#e5e5e5;border-color:rgba(255,255,255,.08);}.actbrow-widget-message code{background:rgba(255,255,255,.12);}.actbrow-widget-message a{color:#e5e5e5;}.actbrow-widget-message pre{background:#0a0a0a;color:#d4d4d4;border-color:rgba(255,255,255,.08);}.actbrow-widget-thinking-dot{background:#e5e5e5;}.actbrow-widget-message-thinking{background:#2a2a2a;border-color:rgba(255,255,255,.08);}.actbrow-widget-tool{background:#2a2a2a;border-color:rgba(255,255,255,.1);color:#e5e5e5;}.actbrow-widget-tool-title{color:#e5e5e5;}.actbrow-widget-tool-icon{background:#333;border-color:rgba(255,255,255,.1);}.actbrow-widget-tool-icon svg{fill:#e5e5e5;}.actbrow-widget-tool-meta{background:#1a1a1a;border-color:rgba(255,255,255,.08);}.actbrow-widget-status{color:#aaa;}.actbrow-widget-footer{background:#0a0a0a;border-color:rgba(255,255,255,.08);}.actbrow-widget-powered{color:#777;}.actbrow-widget-powered svg{fill:#777;}.actbrow-widget-form{background:#1a1a1a;border-color:rgba(255,255,255,.15);}.actbrow-widget-input{background:#252525;color:#e5e5e5;}.actbrow-widget-input::placeholder{color:#777;}.actbrow-widget-input:focus{background:#2a2a2a;}.actbrow-widget-send{background:#e5e5e5;color:#000;}.actbrow-widget-send:hover{background:#fff;box-shadow:0 3px 10px rgba(255,255,255,.2);}}"
    ].join(""), "actbrow-widget-styles-solid");

    var labels = config.labels || {};
    var suggestions = config.suggestions || [
      "Show my orders",
      "Open my profile",
      "Take me to settings"
    ];
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

    var header = document.createElement("div");
    header.className = "actbrow-widget-header";
    header.innerHTML = '<div class="actbrow-widget-header-content">' +
      '<div class="actbrow-widget-title"><div class="actbrow-widget-title-icon"><svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm-1-13h2v6h-2zm0 8h2v2h-2z"/></svg></div>' + escapeHtml(labels.title || "ActBrow Assistant") + '</div>' +
      '<div class="actbrow-widget-subtitle">' + escapeHtml(labels.subtitle || "Ask, navigate, and act inside this app") + '</div>' +
      '<div class="actbrow-widget-badge"><span class="actbrow-widget-badge-dot"></span>' +
      '<span class="actbrow-widget-badge-text">' + escapeHtml(labels.badge || "Live in your app") + '</span></div></div>';

    var closeButton = document.createElement("button");
    closeButton.type = "button";
    closeButton.className = "actbrow-widget-close";
    closeButton.setAttribute("aria-label", labels.close || "Close assistant");
    closeButton.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>';
    header.appendChild(closeButton);

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

    var input = document.createElement("input");
    input.className = "actbrow-widget-input";
    input.type = "text";
    input.placeholder = labels.placeholder || "Ask me to navigate, click, read, or help";
    input.setAttribute("aria-label", "Message input");

    var send = document.createElement("button");
    send.className = "actbrow-widget-send";
    send.type = "submit";
    send.setAttribute("aria-label", "Send message");
    send.innerHTML = '<svg viewBox="0 0 24 24"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg><span>' + escapeHtml(labels.send || "Send") + '</span>';

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
    root.appendChild(panel);
    root.appendChild(launcher);

    (config.mount || document.body).appendChild(root);

    var client = createActbrowClient({
      baseUrl: config.baseUrl,
      assistantId: config.assistantId,
      debug: !!config.debug
    });
    global.ActbrowWidgetClient = client;
    var isOpen = false;
    var isSending = false;
    var thinkingRow = null;
    var statusMode = "idle";
    var hasSubmittedMessage = false;

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
      send.disabled = nextValue;
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

    function appendMessage(role, content) {
      var row = appendRow(role, role === "user" ? (labels.you || "You") : (labels.assistant || "Assistant"));
      var item = document.createElement("div");
      item.className = "actbrow-widget-message " +
        (role === "user" ? "actbrow-widget-message-user" : "actbrow-widget-message-assistant");
      item.textContent = content;
      row.appendChild(item);
      scrollToBottom();
      return row;
    }

    function appendToolActivity(title, meta) {
      var row = appendRow("system", labels.activity || "Activity");
      var item = document.createElement("div");
      item.className = "actbrow-widget-tool";
      item.innerHTML = '<div class="actbrow-widget-tool-title"><div class="actbrow-widget-tool-icon"><svg viewBox="0 0 24 24"><path d="M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z"/></svg></div>' + escapeHtml(title) + '</div>' +
        '<div class="actbrow-widget-tool-meta">' + escapeHtml(meta || "") + '</div>';
      row.appendChild(item);
      scrollToBottom();
      return row;
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
      removeEmptyState();
      appendMessage("user", text);
      input.value = "";
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
      if (document.getElementById("actbrow-widget-empty-state")) {
        return;
      }
      var item = document.createElement("div");
      item.id = "actbrow-widget-empty-state";
      item.className = "actbrow-widget-empty";
      item.innerHTML =
        '<div class="actbrow-widget-empty-title">' + escapeHtml(labels.emptyTitle || "How can I help you today?") + '</div>' +
        '<div class="actbrow-widget-empty-desc">' + escapeHtml(labels.emptyDesc || "Ask for help or ask me to do something in this app.") + '</div>' +
        '<div class="actbrow-widget-suggestions"></div>';
      messages.appendChild(item);
      var suggestionContainer = item.querySelector(".actbrow-widget-suggestions");
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

    function openPanel() {
      isOpen = true;
      panel.classList.remove("actbrow-widget-hidden");
      launcher.setAttribute("aria-expanded", "true");
      if (!messages.childElementCount) {
        var welcomeRow = appendRow("assistant", labels.assistant || "Assistant");
        var welcomeItem = document.createElement("div");
        welcomeItem.className = "actbrow-widget-message actbrow-widget-message-assistant";
        welcomeItem.innerHTML = '<strong>' + escapeHtml(labels.welcomeTitle || "Hi there! 👋") + '</strong><br/>' + escapeHtml(labels.welcome || "I can answer questions and also navigate and act inside this page.");
        welcomeRow.appendChild(welcomeItem);
      }
      if (!hasSubmittedMessage) {
        renderEmptyState();
      } else {
        removeEmptyState();
      }
      input.focus();
    }

    function closePanel() {
      isOpen = false;
      panel.classList.add("actbrow-widget-hidden");
      launcher.setAttribute("aria-expanded", "false");
      launcher.focus();
    }

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

    client.on("tool.call.requested", function (event) {
      if (event && event.payload) {
        ensureThinkingRow();
        setStatus("Running " + event.payload.toolKey + "...");
        appendToolActivity("Running " + event.payload.toolKey, JSON.stringify(event.payload.arguments || {}));
      }
    });

    client.on("tool.call.completed", function (event) {
      var meta = event && event.payload && event.payload.textSummary
        ? event.payload.textSummary
        : "Tool completed successfully.";
      setStatus("Tool completed.");
      appendToolActivity("Tool completed", meta);
    });

    client.on("assistant.message.completed", function (event) {
      removeThinkingRow();
      appendMessage("assistant", event.payload.content);
      setStatus("");
      setSendingState(false);
    });

    client.on("run.failed", function (event) {
      removeThinkingRow();
      appendMessage("assistant", "Request failed: " + event.payload.message);
      setStatus("");
      setSendingState(false);
    });

    form.addEventListener("submit", function (submitEvent) {
      submitEvent.preventDefault();
      var text = input.value.trim();
      if (!text || isSending) {
        return;
      }
      submitPrompt(text);
    });

    return {
      client: client,
      open: openPanel,
      close: closePanel,
      destroy: function () {
        root.remove();
      }
    };
  }

  global.Actbrow = {
    createActbrowClient: createActbrowClient,
    createActbrowWidget: createActbrowWidget
  };
})(window);
