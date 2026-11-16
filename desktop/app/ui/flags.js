// Exit-country flags drawn as SVG. Windows has no flag emoji, so the
// regional-indicator letters showed there instead of flags.

const FLAG_SVG = (() => {
  const h3 = (a, b, c) => `<rect width="30" height="20" fill="${b}"/><rect width="30" height="6.67" fill="${a}"/><rect y="13.33" width="30" height="6.67" fill="${c}"/>`;
  const v3 = (a, b, c) => `<rect width="30" height="20" fill="${b}"/><rect width="10" height="20" fill="${a}"/><rect x="20" width="10" height="20" fill="${c}"/>`;
  const nordic = (field, cross) => `<rect width="30" height="20" fill="${field}"/><rect x="9" width="4" height="20" fill="${cross}"/><rect y="8" width="30" height="4" fill="${cross}"/>`;
  const union = `<rect width="30" height="20" fill="#012169"/>`
    + `<path d="M0 0L30 20M30 0L0 20" stroke="#fff" stroke-width="4"/>`
    + `<path d="M0 0L30 20M30 0L0 20" stroke="#C8102E" stroke-width="1.4"/>`
    + `<path d="M15 0V20M0 10H30" stroke="#fff" stroke-width="6.5"/>`
    + `<path d="M15 0V20M0 10H30" stroke="#C8102E" stroke-width="3.6"/>`;
  const stripes = Array.from({ length: 7 }, (_, i) => `<rect y="${(i * 2 * 20) / 13}" width="30" height="${20 / 13}" fill="#B22234"/>`).join("");
  const stars = Array.from({ length: 20 }, (_, i) => `<circle cx="${1.3 + (i % 5) * 2.6}" cy="${1.3 + Math.floor(i / 5) * 2.6}" r="0.55" fill="#fff"/>`).join("");
  const star = (x, y, r) => {
    const points = Array.from({ length: 14 }, (_, i) => {
      const radius = i % 2 ? r * 0.45 : r;
      const angle = (Math.PI * i) / 7 - Math.PI / 2;
      return `${(x + radius * Math.cos(angle)).toFixed(2)},${(y + radius * Math.sin(angle)).toFixed(2)}`;
    });
    return `<polygon points="${points.join(" ")}" fill="#fff"/>`;
  };
  return {
    DE: h3("#000", "#DD0000", "#FFCE00"),
    NL: h3("#AE1C28", "#fff", "#21468B"),
    AT: h3("#C8102E", "#fff", "#C8102E"),
    FR: v3("#002395", "#fff", "#ED2939"),
    SE: nordic("#006AA7", "#FECC00"),
    FI: nordic("#fff", "#002F6C"),
    CH: `<rect width="30" height="20" fill="#DA291C"/><rect x="13" y="4" width="4" height="12" fill="#fff"/><rect x="9" y="8" width="12" height="4" fill="#fff"/>`,
    JP: `<rect width="30" height="20" fill="#fff"/><circle cx="15" cy="10" r="6" fill="#BC002D"/>`,
    SG: `<rect width="30" height="20" fill="#fff"/><rect width="30" height="10" fill="#EF3340"/><circle cx="6.5" cy="5" r="3.3" fill="#fff"/><circle cx="7.9" cy="5" r="3.1" fill="#EF3340"/>`
      + [[10.2, 2.6], [12.4, 4.2], [11.6, 6.8], [8.8, 6.8], [8, 4.2]].map(([x, y]) => `<circle cx="${x}" cy="${y}" r="0.6" fill="#fff"/>`).join(""),
    US: `<rect width="30" height="20" fill="#fff"/>${stripes}<rect width="13" height="${(7 * 20) / 13}" fill="#3C3B6E"/>${stars}`,
    GB: union,
    CA: `<rect width="30" height="20" fill="#fff"/><rect width="7.5" height="20" fill="#D52B1E"/><rect x="22.5" width="7.5" height="20" fill="#D52B1E"/>`
      + `<path d="M15 3.2l1.2 2.4 1.4-.7-.5 3.4 2-2.1.5 1.2 2-.4-.7 2.1.9.5-3.2 2.7.4 1.2-3.3-.5v3h-.6v-3l-3.3.5.4-1.2-3.2-2.7.9-.5-.7-2.1 2 .4.5-1.2 2 2.1-.5-3.4 1.4.7z" fill="#D52B1E"/>`,
    AU: `<rect width="30" height="20" fill="#012169"/><g transform="scale(0.5)">${union}</g>`
      + star(7.5, 15, 2.4) + star(22.5, 16.5, 1.3) + star(22.5, 3.8, 1.3) + star(18.6, 8.3, 1.3) + star(26.2, 7.3, 1.3) + star(24.3, 11.2, 0.8),
  };
})();

/** A country's flag as an SVG string, or a globe for "any". */
function flagSvg(code) {
  const body = FLAG_SVG[code];
  if (!body) {
    return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.7 2.5 15.3 0 18M12 3c-2.5 2.7-2.5 15.3 0 18"/></svg>`;
  }
  return `<svg viewBox="0 0 30 20" aria-hidden="true">${body}</svg>`;
}
