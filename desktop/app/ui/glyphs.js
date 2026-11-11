// The Android app's line glyphs and app badges, as SVG on a 24-unit grid.
"use strict";

const Glyphs = (() => {
  // An arc the way Compose draws one: degrees clockwise from three o'clock.
  function arc(cx, cy, r, start, sweep) {
    const at = (deg) => [cx + r * Math.cos((deg * Math.PI) / 180), cy + r * Math.sin((deg * Math.PI) / 180)];
    const [x0, y0] = at(start);
    const [x1, y1] = at(start + sweep);
    return `M${x0.toFixed(2)} ${y0.toFixed(2)}A${r} ${r} 0 ${sweep > 180 ? 1 : 0} 1 ${x1.toFixed(2)} ${y1.toFixed(2)}`;
  }
  const star = (cx, cy, r) =>
    `M${cx} ${cy - r}Q${cx} ${cy} ${cx + r} ${cy}Q${cx} ${cy} ${cx} ${cy + r}Q${cx} ${cy} ${cx - r} ${cy}Q${cx} ${cy} ${cx} ${cy - r}Z`;
  const letterH = "M3 6V18M10 6V18M3 12H10";

  const PATHS = {
    chevron: "M9.5 6 15.5 12 9.5 18",
    down: "M12 5V19M6.5 13.5 12 19 17.5 13.5",
    up: "M12 19V5M6.5 10.5 12 5 17.5 10.5",
    pulse: "M2.5 12H7L9.5 6 14 18.5 16.5 12H21.5",
    clock: `${arc(12, 12, 9, 0, 359.9)}M12 7V12L15.5 14`,
    sparkle: star(10.5, 13.5, 7.5) + star(18.5, 5.5, 3),
    h3: letterH + arc(17, 9, 3, 200, 250) + arc(17, 15, 3, 270, 250),
    h2: letterH + arc(17, 9, 3, 200, 205) + "M19.12 11.12 14 18H20",
    serpent: `${arc(12, 12, 9, 0, 359.9)}M8 17C13 17.5 15.5 13 12 11.5 8.5 10 10 6 15.5 7`,
    cloud: `M7 18A3.5 3.5 0 0 1 7 11A5 5 0 0 1 17 11A3.5 3.5 0 0 1 17 18Z${arc(12, 13.5, 2.2, 0, 359.9)}`,
    shield: "M12 2.8 19.5 5.6V11C19.5 15.8 16.4 19.6 12 21.2 7.6 19.6 4.5 15.8 4.5 11V5.6Z",
    sliders: "M4 6H14M18 6H20M4 12H6M10 12H20M4 18H12M16 18H20" + arc(16, 6, 2, 0, 359.9) + arc(8, 12, 2, 0, 359.9) + arc(14, 18, 2, 0, 359.9),
    terminal: "M5 7 10 12 5 17M12 18H19",
  };
  const DOTS = { serpent: [15.5, 9.6, 1.1] };

  function svg(kind, width = 1.8) {
    const dot = DOTS[kind] ? `<circle cx="${DOTS[kind][0]}" cy="${DOTS[kind][1]}" r="${DOTS[kind][2]}" fill="currentColor" stroke="none"/>` : "";
    return `<svg class="glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${PATHS[kind]}"/>${dot}</svg>`;
  }

  // Brand-coloured round badges for the apps a protocol suits.
  const BADGES = {
    Telegram: `<rect width="24" height="24" fill="#2AABEE"/><path d="M5 11.6 18.4 6.4 16.1 17.6 11.7 14.5 9.7 16.6 9.5 13.4 15.2 8.7 8.6 12.9Z" fill="#fff"/>`,
    YouTube: `<rect width="24" height="24" fill="#FF0033"/><path d="M9.6 7.8 16.6 12 9.6 16.2Z" fill="#fff"/>`,
    Instagram: `<defs><linearGradient id="ig" x1="0" y1="1" x2="1" y2="0"><stop offset="0" stop-color="#FEDA75"/><stop offset=".3" stop-color="#FA7E1E"/><stop offset=".55" stop-color="#D62976"/><stop offset=".8" stop-color="#962FBF"/><stop offset="1" stop-color="#4F5BD5"/></linearGradient></defs><rect width="24" height="24" fill="url(#ig)"/><rect x="6.2" y="6.2" width="11.6" height="11.6" rx="3.6" fill="none" stroke="#fff" stroke-width="1.7"/><circle cx="12" cy="12" r="2.8" fill="none" stroke="#fff" stroke-width="1.7"/><circle cx="15.4" cy="8.6" r=".8" fill="#fff"/>`,
    TikTok: `<rect width="24" height="24" fill="#111"/><g fill="none" stroke-width="2" stroke-linecap="round"><path d="M12.6 5.2V14.2A2.4 2.4 0 1 1 10.2 11.8M12.6 5.2C13.2 7 14.6 8.2 16.6 8.4" stroke="#25F4EE"/><path d="M13.6 6V15A2.4 2.4 0 1 1 11.2 12.6M13.6 6C14.2 7.8 15.6 9 17.6 9.2" stroke="#FE2C55"/><path d="M13.1 5.6V14.6A2.4 2.4 0 1 1 10.7 12.2M13.1 5.6C13.7 7.4 15.1 8.6 17.1 8.8" stroke="#fff"/></g>`,
    WhatsApp: `<rect width="24" height="24" fill="#25D366"/><path d="M6.4 17.8 7.3 14.9A6 6 0 1 1 9.4 17Z" fill="none" stroke="#fff" stroke-width="1.6" stroke-linejoin="round"/>`,
    Chrome: `<circle cx="12" cy="12" r="12" fill="#DB4437"/><path d="M12 12 1.6 18A12 12 0 0 0 12 24Z" fill="#0F9D58"/><path d="M12 12H24A12 12 0 0 1 12 24Z" fill="#F4B400"/><path d="M12 12 1.6 18A12 12 0 0 1 1.6 6Z" fill="#0F9D58"/><circle cx="12" cy="12" r="5" fill="#fff"/><circle cx="12" cy="12" r="3.8" fill="#4285F4"/>`,
    X: `<rect width="24" height="24" fill="#000"/><path d="M7 6.5 17 17.5M17 6.5 7 17.5" stroke="#fff" stroke-width="1.8" stroke-linecap="round"/>`,
    Games: `<rect width="24" height="24" fill="#7C4DFF"/><rect x="5" y="8.5" width="14" height="8" rx="4" fill="none" stroke="#fff" stroke-width="1.6"/><path d="M8.5 11V14M7 12.5H10" stroke="#fff" stroke-width="1.5" stroke-linecap="round"/><circle cx="15" cy="11.8" r=".9" fill="#fff"/><circle cx="16.4" cy="13.6" r=".9" fill="#fff"/>`,
  };

  function badge(app) {
    return `<svg class="badge" viewBox="0 0 24 24" role="img" aria-label="${app}"><clipPath id="c-${app}"><circle cx="12" cy="12" r="12"/></clipPath><g clip-path="url(#c-${app})">${BADGES[app]}</g></svg>`;
  }

  return { svg, badge };
})();
