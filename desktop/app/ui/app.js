// Binds the page to the engine: status events in, commands out.
"use strict";

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

// Android's order: Automatic, then Cloudflare's carriers.
const PROTOCOLS = [
  { id: "auto", name: "auto", desc: "auto_desc", glyph: "sparkle", apps: [] },
  { id: "masque-h3", name: "MASQUE", desc: "h3_desc", glyph: "h3", apps: ["WhatsApp", "Chrome"] },
  { id: "masque-h2", name: "MASQUE H2", desc: "h2_desc", glyph: "h2", apps: ["Instagram", "TikTok"] },
  { id: "wire-guard", name: "WireGuard", desc: "wg_desc", glyph: "serpent", apps: ["Games", "X"] },
  { id: "gool", name: "Warp in Warp", desc: "gool_desc", glyph: "cloud", apps: ["Telegram", "YouTube"] },
  { id: "psiphon", name: "Psiphon", desc: "psiphon_desc", glyph: "letterP", apps: ["Telegram", "Chrome"] },
  { id: "tor", name: "Tor", desc: "tor_desc", glyph: "onion", apps: ["Chrome", "X"] },
];
// Psiphon and Tor leave on their own: region unlock has nothing to ride on.
const DIRECT = ["psiphon", "tor"];
const COUNTRIES = ["", "DE", "NL", "US", "GB", "FR", "CA", "SE", "CH", "JP", "SG"];
const SCAN_MODES = ["swift", "turbo", "balanced", "thorough", "stealth", "ironclad"];
const NOIZE = {
  wg: ["amnezia", "off", "light", "balanced", "aggressive"],
  masque: ["off", "light", "firewall", "gfw"],
};
const IP_VERSIONS = ["v4", "v6", "both"];
const PREFS_KEY = "freeport.prefs";

const state = { status: { phase: "idle" }, settings: null, traffic: null, rates: { up: 0, down: 0 }, connectedAt: null, tab: "home" };
const $ = (id) => document.getElementById(id);

// Language and theme are this window's own choices, kept in its storage.
function loadPrefs() {
  try {
    return JSON.parse(localStorage.getItem(PREFS_KEY)) || {};
  } catch {
    return {};
  }
}
function savePrefs(prefs) {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
  } catch {
    /* storage unavailable: the choice lasts for this run */
  }
}
function guessLanguage() {
  const tag = (navigator.language || "en").toLowerCase();
  if (tag.startsWith("fa")) return "fa";
  if (tag.startsWith("ckb")) return "ckb";
  if (tag.startsWith("ku") || tag.startsWith("kmr")) return "kmr";
  return "en";
}
function applyTheme(choice) {
  const dark = choice === "dark" || (choice === "system" && !window.matchMedia("(prefers-color-scheme: light)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  WarpField.repaint();
  Portal.repaint();
}

function toast(text) {
  const node = $("toast");
  node.textContent = text;
  node.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { node.hidden = true; }, 3200);
}

function capitalize(word) {
  return word.charAt(0).toUpperCase() + word.slice(1);
}
function formatDuration(seconds) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const two = (n) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${two(m)}:${two(s)}` : `${two(m)}:${two(s)}`;
}
function countryLabel(code) {
  if (!code) return t("region_any");
  const flag = String.fromCodePoint(...[...code].map((c) => 0x1f1e6 + c.charCodeAt(0) - 65));
  return `${flag} ${code}`;
}

// ---- Home ----

function renderStatus() {
  const s = state.status;
  const connected = s.phase === "connected";
  setHeadline(t(`state_${s.phase}`), connected);
  const detail = $("detail");
  detail.classList.toggle("bad", s.phase === "reconnecting");
  detail.textContent = s.phase === "reconnecting" ? s.reason || "" : s.protocol_name ? `${s.protocol_name}${s.via_auto ? " · " + t("auto") : ""}${s.relayed ? " · Psiphon" : ""}` : "";
  const action = $("action");
  action.textContent = connected ? t("disconnect") : s.phase === "idle" ? t("connect") : t("cancel");
  action.classList.toggle("connected", connected);
  $("portal").setAttribute("aria-label", action.textContent);
  $("stats").classList.toggle("open", connected);
  $("stat-ping").textContent = s.latency_ms != null ? String(s.latency_ms) : "—";
  $("ping-box").className = `stat ${pingTone(s.latency_ms)}`;
  const claude = $("claude");
  claude.hidden = s.claude_open == null;
  claude.textContent = s.claude_open ? t("claude_open") : t("claude_refused");
  claude.className = `pill ${s.claude_open ? "ok" : "bad"}`;
  state.connectedAt = connected && s.connected_secs != null ? Date.now() - s.connected_secs * 1000 : null;
  renderTimer();
  renderProtocols();
  Portal.setPhase(s.phase);
  WarpField.setMood(moodOf(s.phase));
}

function renderTimer() {
  $("timer").classList.toggle("on", !!state.connectedAt);
  $("timer-text").textContent = state.connectedAt ? formatDuration(Math.floor((Date.now() - state.connectedAt) / 1000)) : "";
}

// The headline slides up into place when it changes, as on Android.
function setHeadline(text, connected) {
  const node = $("headline");
  node.classList.toggle("connected", connected);
  if (node.textContent === text) return;
  node.textContent = text;
  node.classList.remove("enter");
  void node.offsetWidth;
  node.classList.add("enter");
}

function pingTone(ms) {
  if (ms == null) return "faint";
  return ms < 150 ? "mint" : ms < 400 ? "amber" : "rose";
}

function splitRate(bytesPerSecond) {
  const kb = bytesPerSecond / 1024;
  return kb >= 1024 ? [(kb / 1024).toFixed(1), "MB/s"] : [String(Math.round(kb)), "KB/s"];
}

function hydrateGlyphs(root = document) {
  root.querySelectorAll("i[data-glyph]").forEach((node) => { node.innerHTML = Glyphs.svg(node.dataset.glyph); });
}

function protocolName(p) {
  return p.id === "auto" ? t("auto") : p.name;
}

function glyphTile(p) {
  return `<span class="tile">${Glyphs.svg(p.glyph, 1.6)}</span>`;
}

function statusDot(live) {
  return `<span class="status-dot ${live ? "live" : "rising"}"></span>`;
}

// One folding row: closed it shows the choice, open it lists every protocol.
function renderProtocols() {
  const chosenId = (state.settings && state.settings.protocol) || "auto";
  const chosen = PROTOCOLS.find((p) => p.id === chosenId) || PROTOCOLS[0];
  const phase = state.status.phase;
  const active = phase !== "idle" && phase !== "disconnecting" ? state.status.protocol : null;
  const live = phase === "connected";

  const head = $("picker-head");
  const picked = chosen.id === "auto" && active && active !== "auto" ? PROTOCOLS.find((p) => p.id === active) : null;
  head.innerHTML = `${glyphTile(chosen)}<span class="name"></span>${picked ? `<span class="sep">·</span><span class="picked"></span>` : ""}${active ? statusDot(live) : ""}<span class="grow"></span><span class="chevron">${Glyphs.svg("chevron", 2)}</span>`;
  head.querySelector(".name").textContent = protocolName(chosen);
  if (picked) head.querySelector(".picked").textContent = picked.name;
  head.classList.add("lit");

  $("protocols").replaceChildren(
    ...PROTOCOLS.map((p) => {
      const row = document.createElement("button");
      row.className = `protocol${p.id === chosen.id ? " lit" : ""}`;
      row.setAttribute("role", "radio");
      row.setAttribute("aria-checked", String(p.id === chosen.id));
      row.title = t(p.desc);
      const badges = p.apps.length ? `<span class="badges">${p.apps.map((a) => Glyphs.badge(a)).join("")}</span>` : "";
      row.innerHTML = `${glyphTile(p)}<span class="grow line"><span class="name"></span>${active === p.id ? statusDot(live) : ""}</span>${badges}<span class="mark"></span>`;
      row.querySelector(".name").textContent = protocolName(p);
      row.addEventListener("click", () => { setPicker(false); chooseProtocol(p.id); });
      return row;
    }),
  );
}

function setPicker(open) {
  $("picker").classList.toggle("open", open);
  $("picker-body").classList.toggle("open", open);
  $("picker-head").setAttribute("aria-expanded", String(open));
}

async function chooseProtocol(id) {
  if (!state.settings || state.settings.protocol === id) return;
  await saveSettings({ ...state.settings, protocol: id }, false);
  if (state.status.phase !== "idle") await invoke("connect").catch((e) => toast(String(e)));
}

async function toggle() {
  const command = state.status.phase === "idle" ? "connect" : "disconnect";
  try {
    await invoke(command);
  } catch (error) {
    toast(String(error));
  }
}

// ---- Settings ----

function fillSelect(select, values, label) {
  select.replaceChildren(...values.map((value) => new Option(label(value), value)));
}

function renderSettings() {
  PerApp.render();
  const s = state.settings;
  if (!s) return;
  const transport = s.protocol === "wire-guard" || s.protocol === "gool" ? "wg" : "masque";
  fillSelect(document.querySelector('[data-setting="noize"]'), NOIZE[transport], (v) => (v === "amnezia" ? "AmneziaWG" : v === "gfw" ? "GFW" : capitalize(v)));
  document.querySelectorAll("[data-setting]").forEach((input) => {
    const value = s[input.dataset.setting];
    if (input.type === "checkbox") input.checked = !!value;
    else input.value = value ?? "";
  });
  if (!NOIZE[transport].includes(s.noize)) document.querySelector('[data-setting="noize"]').value = NOIZE[transport][0];
  $("unlock").checked = s.region_unlock;
  const direct = DIRECT.includes(s.protocol);
  $("unlock").disabled = direct;
  $("unlock").closest(".card, .row, label")?.classList.toggle("muted", direct);
  $("country").value = s.exit_country || "";
  Choices.syncAll();
}

function readSettings() {
  const next = { ...state.settings };
  document.querySelectorAll("[data-setting]").forEach((input) => {
    const key = input.dataset.setting;
    if (input.type === "checkbox") next[key] = input.checked;
    else if (input.type === "number") next[key] = Number(input.value);
    else next[key] = input.value.trim();
  });
  return next;
}

async function saveSettings(next, announce = true) {
  try {
    state.settings = await invoke("save_settings", { settings: next });
    $("bind-error").hidden = true;
    renderSettings();
    renderProtocols();
    if (announce) toast(t("saved"));
  } catch (error) {
    toast(String(error));
    renderSettings();
  }
}

// ---- Logs ----

async function refreshLogs() {
  if (state.tab !== "logs") return;
  const box = $("logs");
  const following = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
  const lines = await invoke("logs", { limit: 400 }).catch(() => []);
  box.replaceChildren(
    ...lines.map((line) => {
      const row = document.createElement("div");
      const kind = line.level === "info" && line.text.startsWith("[+]") ? "good" : line.level;
      row.className = kind;
      const time = document.createElement("time");
      time.textContent = new Date(line.at).toLocaleTimeString([], { hour12: false });
      row.append(time, line.text);
      return row;
    }),
  );
  if (following) box.scrollTop = box.scrollHeight;
}

// ---- Traffic ----

async function refreshTraffic() {
  if (state.status.phase !== "connected") {
    state.traffic = null;
    return;
  }
  const now = await invoke("traffic").catch(() => null);
  if (!now) return;
  if (state.traffic) {
    state.rates = { up: Math.max(0, now.up - state.traffic.up), down: Math.max(0, now.down - state.traffic.down) };
  }
  state.traffic = now;
  const [down, downUnit] = splitRate(state.rates.down);
  const [up, upUnit] = splitRate(state.rates.up);
  $("stat-down").textContent = down;
  $("unit-down").textContent = downUnit;
  $("stat-up").textContent = up;
  $("unit-up").textContent = upUnit;
}

// ---- Wiring ----

function showTab(tab) {
  state.tab = tab;
  document.querySelectorAll(".tabbar button").forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
  ["home", "settings", "logs"].forEach((name) => {
    const section = $(`tab-${name}`);
    const showing = name === tab;
    if (showing && section.hidden) {
      section.classList.remove("enter");
      void section.offsetWidth;
      section.classList.add("enter");
    }
    section.hidden = !showing;
  });
  if (tab === "logs") refreshLogs().then(() => { $("logs").scrollTop = $("logs").scrollHeight; });
}

function placeFieldFocus() {
  const rect = $("portal").getBoundingClientRect();
  const visible = state.tab === "home" && rect.width > 0;
  WarpField.setFocus(visible ? { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 } : null);
}

function wire(prefs) {
  $("portal").addEventListener("click", toggle);
  $("picker-head").addEventListener("click", () => setPicker(!$("picker").classList.contains("open")));
  document.querySelectorAll(".tabbar button").forEach((b) => b.addEventListener("click", () => { showTab(b.dataset.tab); placeFieldFocus(); }));
  document.querySelectorAll("[data-link]").forEach((b) => b.addEventListener("click", () => invoke("open_link", { url: b.dataset.link }).catch((e) => toast(String(e)))));
  $("unlock").addEventListener("change", (e) => saveSettings({ ...state.settings, region_unlock: e.target.checked }, false));
  $("country").addEventListener("change", (e) => saveSettings({ ...state.settings, exit_country: e.target.value, region_unlock: true }, false));
  $("save").addEventListener("click", () => saveSettings(readSettings()));
  document.querySelector('[data-setting="system_proxy"]').addEventListener("change", () => saveSettings(readSettings(), false));
  $("copy-logs").addEventListener("click", async () => {
    await navigator.clipboard.writeText($("logs").innerText).catch(() => {});
    toast(t("copied"));
  });
  $("language").addEventListener("change", (e) => {
    savePrefs({ ...loadPrefs(), language: e.target.value });
    applyLanguage(e.target.value);
    fillStaticSelects();
    renderStatus();
  });
  $("theme").addEventListener("change", (e) => {
    savePrefs({ ...loadPrefs(), theme: e.target.value });
    applyTheme(e.target.value);
  });
  window.matchMedia("(prefers-color-scheme: light)").addEventListener("change", () => applyTheme(loadPrefs().theme || "dark"));
  document.querySelector("main").addEventListener("scroll", placeFieldFocus, { passive: true });
  window.addEventListener("resize", placeFieldFocus);
  $("language").value = prefs.language;
  $("theme").value = prefs.theme;
}

function fillStaticSelects() {
  fillSelect($("country"), COUNTRIES, countryLabel);
  fillSelect(document.querySelector('[data-setting="scan_mode"]'), SCAN_MODES, capitalize);
  fillSelect(document.querySelector('[data-setting="ip_version"]'), IP_VERSIONS, (v) => ({ v4: "IPv4", v6: "IPv6", both: "IPv4 + IPv6" })[v]);
  fillSelect($("language"), Object.keys(LANGUAGES), (v) => LANGUAGES[v].label);
  fillSelect($("theme"), ["dark", "light", "system"], (v) => t(`theme_${v}`));
  const prefs = loadPrefs();
  $("language").value = prefs.language || guessLanguage();
  $("theme").value = prefs.theme || "dark";
  renderSettings();
  Choices.syncAll();
}

async function start() {
  if (LITE) document.body.classList.add("lite");
  hydrateGlyphs();
  const prefs = { language: guessLanguage(), theme: "dark", ...loadPrefs() };
  applyLanguage(prefs.language);
  applyTheme(prefs.theme);
  const snapshot = await invoke("snapshot");
  state.settings = snapshot.settings;
  state.status = snapshot.status;
  $("version").textContent = `v${snapshot.version}`;
  PerApp.init({ invoke, toast, settings: () => state.settings, save: (next) => saveSettings(next, false) });
  if (snapshot.bind_error) {
    const box = $("bind-error");
    box.hidden = false;
    box.textContent = `${t("port_busy")} (${snapshot.bind_error})`;
  }
  fillStaticSelects();
  wire(prefs);
  renderStatus();
  requestAnimationFrame(placeFieldFocus);
  await listen("status", (event) => {
    state.status = event.payload;
    renderStatus();
  });
  setInterval(() => { renderTimer(); refreshTraffic(); }, 1000);
  setInterval(refreshLogs, 1500);
}

start().catch((error) => toast(String(error)));
