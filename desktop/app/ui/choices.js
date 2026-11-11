// Android's choice controls in place of drop-downs: a segmented picker with
// a sliding pill for a few options, chips for longer lists. Each one mirrors
// a hidden <select>, which stays the source of truth for the rest of the page.
"use strict";

const Choices = (() => {
  const SEGMENTED_MAX = 4;
  const views = new WeakMap();

  function viewFor(select) {
    let view = views.get(select);
    if (!view) {
      view = document.createElement("div");
      view.setAttribute("role", "radiogroup");
      select.after(view);
      select.hidden = true;
      views.set(select, view);
    }
    return view;
  }

  function choose(select, value) {
    if (select.value === value) return;
    select.value = value;
    select.dispatchEvent(new Event("change", { bubbles: true }));
    sync(select);
  }

  function option(select, opt, index) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "opt";
    button.setAttribute("role", "radio");
    button.style.setProperty("--i", index);
    button.textContent = opt.textContent;
    const active = opt.value === select.value;
    button.classList.toggle("on", active);
    button.setAttribute("aria-checked", String(active));
    button.addEventListener("click", () => choose(select, opt.value));
    return button;
  }

  /** Redraws the control for `select` from its options and value. */
  function sync(select) {
    const view = viewFor(select);
    const options = [...select.options];
    const segmented = options.length <= SEGMENTED_MAX && !select.dataset.chips;
    view.className = segmented ? "segmented" : "chips";
    const index = Math.max(0, options.findIndex((o) => o.value === select.value));
    view.style.setProperty("--n", options.length);
    view.style.setProperty("--i", index);
    // The same pill is kept so it slides to the new choice instead of jumping.
    const kept = view.querySelector(".pill-slide");
    const pill = segmented ? [kept || Object.assign(document.createElement("span"), { className: "pill-slide" })] : [];
    view.replaceChildren(...pill, ...options.map((o, i) => option(select, o, i)));
  }

  function syncAll(root = document) {
    root.querySelectorAll("select").forEach(sync);
  }

  return { sync, syncAll };
})();
