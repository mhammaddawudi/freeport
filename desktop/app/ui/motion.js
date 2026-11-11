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

// ---- Compose's animation specs, so every curve matches the Android app ----

/** A cubic Bézier easing, solved for x the way CSS and Compose do it. */
function cubicBezier(x1, y1, x2, y2) {
  const curve = (a, b, t) => 3 * a * t * (1 - t) ** 2 + 3 * b * t * t * (1 - t) + t ** 3;
  const slope = (a, b, t) => 3 * a * (1 - t) ** 2 + 6 * (b - a) * t * (1 - t) + 3 * (1 - b) * t * t;
  return (x) => {
    if (x <= 0 || x >= 1) return Math.min(1, Math.max(0, x));
    let t = x;
    for (let i = 0; i < 8; i++) {
      const d = slope(x1, x2, t);
      if (Math.abs(d) < 1e-6) break;
      t -= (curve(x1, x2, t) - x) / d;
    }
    return curve(y1, y2, Math.min(1, Math.max(0, t)));
  };
}

const Ease = {
  linear: (t) => t,
  /** FastOutSlowInEasing, Compose's default for tween(). */
  standard: cubicBezier(0.4, 0, 0.2, 1),
};

/** animate*AsState with tween(): restarts from where it is whenever the target changes. */
class Tween {
  constructor(value) {
    this.from = value;
    this.to = value;
    this.start = 0;
    this.duration = 0;
    this.ease = Ease.standard;
  }
  set(to, duration, now, ease = Ease.standard) {
    if (to === this.to) return;
    this.from = this.value(now);
    this.to = to;
    this.start = now;
    this.duration = duration;
    this.ease = ease;
  }
  value(now) {
    if (!this.duration) return this.to;
    const t = Math.min(1, (now - this.start) / this.duration);
    return this.from + (this.to - this.from) * this.ease(t);
  }
  done(now) {
    return now - this.start >= this.duration;
  }
}

/** spring(dampingRatio, stiffness) with unit mass, stepped in small slices. */
class Spring {
  constructor(value, dampingRatio, stiffness) {
    this.x = value;
    this.v = 0;
    this.to = value;
    this.k = stiffness;
    this.c = 2 * dampingRatio * Math.sqrt(stiffness);
  }
  step(dt) {
    const slices = Math.max(1, Math.ceil(dt / 0.004));
    const h = dt / slices;
    for (let i = 0; i < slices; i++) {
      const a = -this.k * (this.x - this.to) - this.c * this.v;
      this.v += a * h;
      this.x += this.v * h;
    }
    if (this.settled()) {
      this.x = this.to;
      this.v = 0;
    }
    return this.x;
  }
  settled() {
    return Math.abs(this.x - this.to) < 0.0005 && Math.abs(this.v) < 0.005;
  }
}

/**
 * rememberLoop: a 0..1 phase that advances only while running and holds
 * where it is when stopped. `reverse` runs it back down instead of wrapping.
 */
class Loop {
  constructor(reverse = false) {
    this.reverse = reverse;
    this.phase = 0;
    this.down = false;
  }
  advance(dt, durationMs) {
    const step = (dt * 1000) / durationMs;
    if (!this.reverse) {
      this.phase = (this.phase + step) % 1;
      return this.phase;
    }
    this.phase += this.down ? -step : step;
    if (this.phase >= 1) { this.phase = 1; this.down = true; }
    if (this.phase <= 0) { this.phase = 0; this.down = false; }
    return this.phase;
  }
}

function mixHex(a, b, t) {
  const rgb = mix(a, b, t);
  return "#" + rgb.map((n) => n.toString(16).padStart(2, "0")).join("");
}
