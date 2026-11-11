// Shared by the warp field and the portal: the palette, the colour of each
// state, and whether this machine gets the light version of the look.
"use strict";

const PALETTES = {
  dark: {
    void: "#04040B", ink: "#F4F4FF", faint: "#60607F", raised: "#16162B",
    violet: "#8B5CFF", indigo: "#4F7CFF", aqua: "#22D3EE", mint: "#2EF2A0", flare: "#FF4FD8",
    nebula: 1, starAlpha: 1, glassEdge: 0.22,
  },
  light: {
    void: "#F3F2FA", ink: "#14122A", faint: "#8D8BA6", raised: "#FFFFFF",
    violet: "#6D3CF5", indigo: "#3B5BDB", aqua: "#0891B2", mint: "#07A36A", flare: "#C026D3",
    nebula: 0.6, starAlpha: 0.55, glassEdge: 0.6,
  },
};

function palette() {
  return PALETTES[document.documentElement.dataset.theme === "light" ? "light" : "dark"];
}

/** idle, busy or connected: what the motion and colour follow. */
function moodOf(phase) {
  if (phase === "connected") return "connected";
  if (phase === "idle") return "idle";
  return "busy";
}

function toneOf(mood) {
  const p = palette();
  if (mood === "connected") return [p.mint, p.aqua];
  if (mood === "busy") return [p.flare, p.violet];
  return [p.violet, p.indigo];
}

/**
 * Low-end machines, and anyone who asked the system for less motion, get
 * fewer stars and no motion except while connecting.
 */
const LITE = (() => {
  const reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const cores = navigator.hardwareConcurrency || 8;
  const memory = navigator.deviceMemory || 8;
  return reduced || cores <= 2 || memory <= 2;
})();

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function mix(a, b, t) {
  const x = hexToRgb(a);
  const y = hexToRgb(b);
  return x.map((v, i) => Math.round(v + (y[i] - v) * t));
}

function rgba(rgb, alpha) {
  return `rgba(${rgb[0]},${rgb[1]},${rgb[2]},${Math.max(0, Math.min(1, alpha))})`;
}

/** Moves `value` towards `target`, framerate-independent; tau in seconds. */
function approach(value, target, dt, tau) {
  return value + (target - value) * (1 - Math.exp(-dt / tau));
}

/** A canvas sized for the screen's pixel density. */
function fitCanvas(canvas) {
  const ratio = Math.min(window.devicePixelRatio || 1, LITE ? 1.5 : 2);
  const rect = canvas.getBoundingClientRect();
  const width = Math.max(1, Math.round(rect.width * ratio));
  const height = Math.max(1, Math.round(rect.height * ratio));
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  const context = canvas.getContext("2d");
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  return { context, width: rect.width, height: rect.height };
}
