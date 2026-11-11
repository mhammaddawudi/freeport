// The warp field behind everything: still at rest, streaks of light while a
// session comes up, a slow cruise once connected. Two canvases: the nebula
// repaints only when its colour or size changes, the stars only while they
// move, so an idle window asks for no frames at all.
"use strict";

const WarpField = (() => {
  const STARS = LITE ? 60 : 120;
  const NEAREST = 0.035;
  const nebulaCanvas = document.getElementById("nebula");
  const starCanvas = document.getElementById("stars");

  // A fixed seed: the same sky every launch.
  let seed = 1819;
  const random = () => {
    seed = (seed * 16807) % 2147483647;
    return (seed - 1) / 2147483646;
  };
  const stars = Array.from({ length: STARS }, () => ({
    x: random() * 2 - 1, y: random() * 2 - 1, depth: random(), size: 0.6 + random() * 1.2, hue: random(),
  }));

  let mood = "idle";
  let focus = null;
  let speed = 0;
  let streak = 0.15;
  let travel = 0;
  let last = 0;
  let running = false;

  const targets = () => ({
    speed: mood === "busy" ? 0.85 : mood === "connected" && !LITE ? 0.1 : 0,
    streak: mood === "busy" ? 1 : 0.15,
  });

  function origin(width, height) {
    return focus || { x: width / 2, y: height * 0.36 };
  }

  function paintNebula() {
    const { context: g, width, height } = fitCanvas(nebulaCanvas);
    const p = palette();
    const [primary, secondary] = toneOf(mood);
    const o = origin(width, height);
    g.fillStyle = p.void;
    g.fillRect(0, 0, width, height);
    const blob = (x, y, r, colour, alpha) => {
      const gradient = g.createRadialGradient(x, y, 0, x, y, r);
      gradient.addColorStop(0, rgba(hexToRgb(colour), alpha * p.nebula));
      gradient.addColorStop(1, rgba(hexToRgb(colour), 0));
      g.fillStyle = gradient;
      g.fillRect(0, 0, width, height);
    };
    blob(o.x, o.y, width * 0.95, primary, 0.24);
    blob(width * 0.92, height * 0.06, width * 0.7, secondary, 0.16);
    blob(width * 0.05, height * 0.8, width * 0.8, secondary, 0.1);
  }

  function paintStars() {
    const { context: g, width, height } = fitCanvas(starCanvas);
    g.clearRect(0, 0, width, height);
    const p = palette();
    const [primary] = toneOf(mood);
    const o = origin(width, height);
    const reach = Math.max(width, height) * 0.55 * 0.12;
    g.lineCap = "round";
    for (const star of stars) {
      const z = NEAREST + ((((star.depth - travel) % 1) + 1) % 1) * (1 - NEAREST);
      const tail = Math.min(1, z + 0.02 + 0.16 * streak);
      const near = 1 - z;
      const alpha = Math.min(1, near * near * 1.2) * p.starAlpha;
      const tint = star.hue < 0.18 ? p.aqua : star.hue < 0.3 ? p.violet : p.ink;
      g.strokeStyle = rgba(mix(tint, primary, 0.25), alpha);
      g.lineWidth = star.size * (0.6 + 2.2 * near) / 1.4;
      g.beginPath();
      g.moveTo(o.x + (star.x / tail) * reach, o.y + (star.y / tail) * reach);
      g.lineTo(o.x + (star.x / z) * reach, o.y + (star.y / z) * reach);
      g.stroke();
    }
  }

  function frame(now) {
    const dt = last ? Math.min(0.05, (now - last) / 1000) : 0;
    last = now;
    const goal = targets();
    speed = approach(speed, goal.speed, dt, 0.5);
    streak = approach(streak, goal.streak, dt, 0.45);
    travel = (travel + speed * dt) % 1000;
    paintStars();
    const settled = Math.abs(speed - goal.speed) < 0.002 && Math.abs(streak - goal.streak) < 0.002;
    if (goal.speed === 0 && settled) {
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

  function repaint() {
    paintNebula();
    paintStars();
  }

  window.addEventListener("resize", repaint);

  return {
    setMood(next) {
      if (next === mood) return;
      mood = next;
      paintNebula();
      wake();
    },
    setFocus(point) {
      focus = point;
      repaint();
    },
    repaint,
  };
})();
