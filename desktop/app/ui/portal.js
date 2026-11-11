// The connect button: a portal. Three gyroscope rings round a glass core;
// still while disconnected, spinning while a session comes up, an aurora
// ring with light waves leaving it once connected.
"use strict";

const Portal = (() => {
  const canvas = document.getElementById("portal-canvas");
  const GYROS = [
    { reach: 1.4, sweep: 110, turns: 1, offset: 0 },
    { reach: 1.26, sweep: 70, turns: -1.6, offset: 140 },
    { reach: 1.14, sweep: 150, turns: 2.3, offset: 260 },
  ];
  const PROGRESS = { idle: 0, preparing: 0.12, scanning: 0.45, connecting: 0.9, unlocking: 0.95, reconnecting: 0.08, disconnecting: 0, connected: 1 };

  let phase = "idle";
  let pressed = false;
  const v = { glow: 0, busy: 0, grow: 0.88, warmth: 0.46, progress: 0, press: 1 };
  let spin = 0;
  let ripple = 0;
  let pulse = 0;
  let last = 0;
  let running = false;

  const mood = () => moodOf(phase);
  // Motion runs only while connecting, or once connected on machines that can afford it.
  const moving = () => mood() === "busy" || (mood() === "connected" && !LITE);

  function goals() {
    const m = mood();
    return {
      glow: m === "connected" ? 1 : 0,
      busy: m === "busy" ? 1 : 0,
      grow: m === "connected" ? 1 : 0.88,
      warmth: m === "connected" ? 1 : m === "busy" ? 0.66 : 0.46,
      progress: PROGRESS[phase] ?? 0,
      press: pressed ? 0.93 : 1,
    };
  }

  function arcPath(g, cx, cy, r, start, sweep) {
    g.beginPath();
    g.arc(cx, cy, r, (start * Math.PI) / 180, ((start + sweep) * Math.PI) / 180);
  }

  function sweepGradient(g, cx, cy, colours, rotation) {
    if (g.createConicGradient) {
      const gradient = g.createConicGradient(rotation, cx, cy);
      colours.forEach((c, i) => gradient.addColorStop(i / (colours.length - 1), c));
      return gradient;
    }
    return colours[0];
  }

  function draw() {
    const { context: g, width, height } = fitCanvas(canvas);
    g.clearRect(0, 0, width, height);
    // Before layout, or while its tab is hidden, the canvas has no size.
    if (Math.min(width, height) < 40) return;
    const p = palette();
    const [primary, secondary] = toneOf(mood());
    const core = mix(p.faint, primary, v.warmth);
    const rim = mix(p.faint, secondary, v.warmth);
    const cx = width / 2;
    const cy = height / 2;
    const ring = (Math.min(width, height) / 2) * 0.58 * v.grow * v.press * (v.busy > 0.5 ? 1 + 0.02 * pulse : 1);
    const stroke = 7;
    const lit = Math.max(v.glow, v.busy);
    const aurora = [p.violet, p.aqua, p.mint, p.violet];
    const turn = spin * Math.PI * 2;

    // Halo.
    const halo = g.createRadialGradient(cx, cy, 0, cx, cy, ring * 1.75);
    halo.addColorStop(0, rgba(core, 0.1 + 0.22 * lit));
    halo.addColorStop(1, rgba(core, 0));
    g.fillStyle = halo;
    g.fillRect(0, 0, width, height);

    // Light waves once connected.
    if (v.glow > 0.01 && !LITE) {
      for (let i = 0; i < 3; i++) {
        const t = (ripple + i / 3) % 1;
        g.globalAlpha = (1 - t) * 0.45 * v.glow;
        g.strokeStyle = sweepGradient(g, cx, cy, aurora, turn);
        g.lineWidth = 2 * (1 - t) + 0.5;
        g.beginPath();
        g.arc(cx, cy, ring * (1 + 0.62 * t), 0, Math.PI * 2);
        g.stroke();
      }
      g.globalAlpha = 1;
    }

    // Gyroscope rings, each with a satellite at its lit end.
    const busyColours = [rgba(rim, 1), rgba(core, 1), rgba(rim, 1)];
    GYROS.forEach((gyro, index) => {
      const r = ring * gyro.reach;
      g.strokeStyle = rgba(hexToRgb(p.ink), 0.05 + 0.02 * index);
      g.lineWidth = 1;
      g.beginPath();
      g.arc(cx, cy, r, 0, Math.PI * 2);
      g.stroke();
      const angle = gyro.offset + spin * 360 * gyro.turns;
      g.globalAlpha = 0.3 + 0.6 * lit;
      g.strokeStyle = sweepGradient(g, cx, cy, v.busy > v.glow ? busyColours : aurora, (angle * Math.PI) / 180);
      g.lineWidth = 2.2;
      g.lineCap = "round";
      arcPath(g, cx, cy, r, angle, gyro.sweep);
      g.stroke();
      g.globalAlpha = 1;
      const head = ((angle + gyro.sweep) * Math.PI) / 180;
      const hx = cx + r * Math.cos(head);
      const hy = cy + r * Math.sin(head);
      const dot = hexToRgb([p.violet, p.aqua, p.mint][index]);
      const glow = g.createRadialGradient(hx, hy, 0, hx, hy, 9);
      glow.addColorStop(0, rgba(dot, 0.7 * (0.4 + 0.6 * lit)));
      glow.addColorStop(1, rgba(dot, 0));
      g.fillStyle = glow;
      g.fillRect(hx - 9, hy - 9, 18, 18);
      g.fillStyle = rgba(dot, 0.5 + 0.5 * lit);
      g.beginPath();
      g.arc(hx, hy, 2.4, 0, Math.PI * 2);
      g.fill();
    });

    // The core ring: a track, the aurora when connected, progress while busy.
    g.strokeStyle = rgba(hexToRgb(p.ink), 0.1);
    g.lineWidth = stroke;
    g.beginPath();
    g.arc(cx, cy, ring, 0, Math.PI * 2);
    g.stroke();
    if (v.glow > 0.01) {
      g.strokeStyle = sweepGradient(g, cx, cy, aurora, turn);
      g.globalAlpha = 0.16 * v.glow;
      g.lineWidth = stroke * 3.2;
      g.beginPath();
      g.arc(cx, cy, ring, 0, Math.PI * 2);
      g.stroke();
      g.globalAlpha = 1;
      g.lineWidth = stroke;
      arcPath(g, cx, cy, ring, -90, 360 * v.glow);
      g.stroke();
    }
    if (v.busy > 0.01) {
      g.globalAlpha = v.busy;
      g.strokeStyle = sweepGradient(g, cx, cy, busyColours, -Math.PI / 2);
      g.lineWidth = stroke;
      arcPath(g, cx, cy, ring, -90, 360 * v.progress);
      g.stroke();
      if (v.progress > 0.01) {
        const head = ((-90 + 360 * v.progress) * Math.PI) / 180;
        const hx = cx + ring * Math.cos(head);
        const hy = cy + ring * Math.sin(head);
        const size = stroke * (2.4 + 0.6 * pulse);
        const glow = g.createRadialGradient(hx, hy, 0, hx, hy, size);
        glow.addColorStop(0, rgba(core, 0.6));
        glow.addColorStop(1, rgba(core, 0));
        g.fillStyle = glow;
        g.fillRect(hx - size, hy - size, size * 2, size * 2);
        g.fillStyle = "rgba(255,255,255,0.9)";
        g.beginPath();
        g.arc(hx, hy, stroke * 0.42, 0, Math.PI * 2);
        g.fill();
      }
      g.globalAlpha = 1;
    }

    // The glass core.
    const disc = ring - stroke * 1.5;
    const glass = g.createRadialGradient(cx - disc * 0.3, cy - disc * 0.45, 0, cx - disc * 0.3, cy - disc * 0.45, disc * 1.6);
    glass.addColorStop(0, p.raised);
    glass.addColorStop(1, p.void);
    g.fillStyle = glass;
    g.beginPath();
    g.arc(cx, cy, disc, 0, Math.PI * 2);
    g.fill();
    const pool = g.createRadialGradient(cx, cy + disc * 0.35, 0, cx, cy + disc * 0.35, disc);
    pool.addColorStop(0, rgba(core, 0.05 + 0.16 * lit));
    pool.addColorStop(1, rgba(core, 0));
    g.fillStyle = pool;
    g.fill();
    const edge = g.createLinearGradient(0, cy - disc, 0, cy + disc * 0.4);
    edge.addColorStop(0, `rgba(255,255,255,${p.glassEdge})`);
    edge.addColorStop(1, "rgba(255,255,255,0)");
    g.strokeStyle = edge;
    g.lineWidth = 1.2;
    g.stroke();

    // The power mark.
    const mark = ring * 0.36;
    const start = mix(rgbHex(core), p.violet, v.glow);
    const end = mix(rgbHex(core), p.mint, v.glow);
    const brush = g.createLinearGradient(cx - mark, cy - mark, cx + mark, cy + mark);
    brush.addColorStop(0, rgba(start, 1));
    brush.addColorStop(1, rgba(end, 1));
    g.globalAlpha = 0.6 + 0.4 * lit;
    g.strokeStyle = brush;
    g.lineWidth = stroke * 0.85;
    g.lineCap = "round";
    arcPath(g, cx, cy + mark * 0.12, mark, 300, 300);
    g.stroke();
    g.beginPath();
    g.moveTo(cx, cy - mark * 1.15);
    g.lineTo(cx, cy + mark * 0.05);
    g.stroke();
    g.globalAlpha = 1;
  }

  function rgbHex(rgb) {
    return "#" + rgb.map((n) => n.toString(16).padStart(2, "0")).join("");
  }

  function frame(now) {
    const dt = last ? Math.min(0.05, (now - last) / 1000) : 0;
    last = now;
    const goal = goals();
    v.glow = approach(v.glow, goal.glow, dt, 0.3);
    v.busy = approach(v.busy, goal.busy, dt, 0.18);
    v.grow = approach(v.grow, goal.grow, dt, 0.28);
    v.warmth = approach(v.warmth, goal.warmth, dt, 0.2);
    v.press = approach(v.press, goal.press, dt, 0.06);
    // The ring creeps towards each phase's ceiling and closes only once connected.
    v.progress = approach(v.progress, goal.progress, dt, phase === "connected" ? 0.2 : 2.5);
    if (moving()) {
      spin = (spin + dt / (mood() === "busy" ? 2.4 : 14)) % 1;
      ripple = (ripple + dt / 2.8) % 1;
      pulse = 0.5 + 0.5 * Math.sin(now / 350);
    }
    draw();
    const settled = Object.keys(goal).every((key) => Math.abs(v[key] - goal[key]) < 0.003);
    if (!moving() && settled) {
      running = false;
      last = 0;
      return;
    }
    requestAnimationFrame(frame);
  }

  function wake() {
    if (running) return;
    running = true;
    last = 0;
    requestAnimationFrame(frame);
  }

  canvas.parentElement.addEventListener("pointerdown", () => { pressed = true; wake(); });
  ["pointerup", "pointerleave"].forEach((name) => canvas.parentElement.addEventListener(name, () => { pressed = false; wake(); }));
  window.addEventListener("resize", () => { draw(); });

  return {
    setPhase(next) {
      phase = next;
      wake();
    },
    repaint: draw,
  };
})();
