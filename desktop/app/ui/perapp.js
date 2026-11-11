// Per-app proxy: the apps Freeport opens pointed at itself, so only they use
// the tunnel while the system proxy stays off.
"use strict";

const PerApp = (() => {
  let installed = [];
  let ctx = null;

  const chosen = () => ctx.settings().proxied_apps || [];
  const nameOf = (path) => (installed.find((app) => app.path === path) || { name: path.split(/[\\/]/).pop().replace(/\.(app|lnk)$/i, "") }).name;

  function item(name, actions) {
    const row = document.createElement("div");
    row.className = "app-item";
    const label = document.createElement("span");
    label.className = "grow";
    label.textContent = name;
    row.append(label, ...actions);
    return row;
  }

  function button(text, onClick, primary = false) {
    const node = document.createElement("button");
    node.className = primary ? "app-open" : "app-remove";
    node.textContent = text;
    node.addEventListener("click", onClick);
    return node;
  }

  function save(apps) {
    ctx.save({ ...ctx.settings(), proxied_apps: apps });
  }

  async function open(path) {
    try {
      await ctx.invoke("open_app", { path });
      ctx.toast(t("app_opened").replace("{app}", nameOf(path)));
    } catch (error) {
      ctx.toast(String(error));
    }
  }

  function render() {
    if (!ctx) return;
    document.getElementById("open-on-connect").checked = !!ctx.settings().open_apps_on_connect;
    document.getElementById("app-list").replaceChildren(
      ...chosen().map((path) => item(nameOf(path), [button(t("open"), () => open(path), true), button("×", () => save(chosen().filter((p) => p !== path)))])),
    );
    const query = document.getElementById("app-search").value.trim().toLowerCase();
    const matches = query
      ? installed.filter((app) => app.name.toLowerCase().includes(query) && !chosen().includes(app.path)).slice(0, 6)
      : [];
    document.getElementById("app-results").replaceChildren(
      ...matches.map((app) => item(app.name, [button("+", () => {
        document.getElementById("app-search").value = "";
        save([...chosen(), app.path]);
      }, true)])),
    );
  }

  async function init(context) {
    ctx = context;
    document.getElementById("app-search").addEventListener("input", render);
    document.getElementById("open-on-connect").addEventListener("change", (e) => ctx.save({ ...ctx.settings(), open_apps_on_connect: e.target.checked }));
    installed = await ctx.invoke("installed_apps").catch(() => []);
    render();
  }

  return { init, render };
})();
