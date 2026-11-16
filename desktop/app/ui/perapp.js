// Per-app: a list of apps, whether it steers the proxy, TUN or both, and
// whether those apps alone use Freeport or are the ones left out. Changes
// wait in a draft until Apply, which takes effect on the running session.
"use strict";

const PerApp = (() => {
  const KEYS = ["proxied_apps", "proxy_per_app", "tun_per_app", "per_app_except", "open_apps_on_connect"];
  const TARGETS = ["proxy", "tun", "both"];
  const MODES = ["only", "except"];
  let installed = [];
  let ctx = null;
  let draft = null;

  const $ = (id) => document.getElementById(id);
  const saved = () => Object.fromEntries(KEYS.map((key) => [key, ctx.settings()[key]]));
  const current = () => draft || saved();
  const chosen = () => current().proxied_apps || [];
  const nameOf = (path) => (installed.find((app) => app.path === path) || { name: path.split(/[\\/]/).pop().replace(/\.(app|lnk)$/i, "") }).name;
  const dirty = () => !!draft && JSON.stringify(draft) !== JSON.stringify(saved());

  function targetOf(choice) {
    if (choice.proxy_per_app && choice.tun_per_app) return "both";
    return choice.tun_per_app ? "tun" : "proxy";
  }

  function edit(change) {
    draft = { ...current(), ...change };
    render();
  }

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

  async function open(path) {
    try {
      await ctx.invoke("open_app", { path });
      ctx.toast(t("app_opened").replace("{app}", nameOf(path)));
    } catch (error) {
      ctx.toast(String(error));
    }
  }

  // Saves the draft; while connected the proxy and TUN follow at once, and
  // newly listed apps open with the new route.
  async function apply() {
    if (!dirty()) return;
    const before = saved().proxied_apps || [];
    const next = draft;
    await ctx.save({ ...ctx.settings(), ...next });
    draft = null;
    render();
    ctx.toast(t("applied"));
    const routed = next.proxy_per_app && ctx.connected();
    if (routed) next.proxied_apps.filter((path) => !before.includes(path)).forEach(open);
  }

  function fillChoices() {
    const target = $("per-app-target");
    const mode = $("per-app-mode");
    target.replaceChildren(...TARGETS.map((v) => new Option(t(`target_${v}`), v)));
    mode.replaceChildren(...MODES.map((v) => new Option(t(`mode_${v}`), v)));
  }

  function render() {
    if (!ctx) return;
    const choice = current();
    fillChoices();
    $("per-app-target").value = targetOf(choice);
    $("per-app-mode").value = choice.per_app_except ? "except" : "only";
    $("open-on-connect").checked = !!choice.open_apps_on_connect;
    $("apply-per-app").disabled = !dirty();
    $("app-list").replaceChildren(
      ...chosen().map((path) => item(nameOf(path), [button(t("open"), () => open(path), true), button("×", () => edit({ proxied_apps: chosen().filter((p) => p !== path) }))])),
    );
    const query = $("app-search").value.trim().toLowerCase();
    const matches = query
      ? installed.filter((app) => app.name.toLowerCase().includes(query) && !chosen().includes(app.path)).slice(0, 6)
      : [];
    $("app-results").replaceChildren(
      ...matches.map((app) => item(app.name, [button("+", () => {
        $("app-search").value = "";
        edit({ proxied_apps: [...chosen(), app.path] });
      }, true)])),
    );
    Choices.syncAll($("per-app"));
  }

  async function init(context) {
    ctx = context;
    $("app-search").addEventListener("input", render);
    $("open-on-connect").addEventListener("change", (e) => edit({ open_apps_on_connect: e.target.checked }));
    $("per-app-target").addEventListener("change", (e) => edit({ proxy_per_app: e.target.value !== "tun", tun_per_app: e.target.value !== "proxy" }));
    $("per-app-mode").addEventListener("change", (e) => edit({ per_app_except: e.target.value === "except" }));
    $("apply-per-app").addEventListener("click", apply);
    installed = await ctx.invoke("installed_apps").catch(() => []);
    render();
  }

  return { init, render };
})();
