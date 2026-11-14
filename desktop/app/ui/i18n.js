// The four languages the app ships in, with the same words as the Android app.
"use strict";

const LANGUAGES = {
  en: { label: "English", dir: "ltr" },
  ckb: { label: "کوردی", dir: "rtl" },
  kmr: { label: "Kurdî", dir: "ltr" },
  fa: { label: "فارسی", dir: "rtl" },
};

const STRINGS = {
  en: {
    state_idle: "Not connected", state_preparing: "Preparing", state_scanning: "Finding a route",
    state_connecting: "Opening the tunnel", state_unlocking: "Moving the exit abroad", state_connected: "Connected",
    state_reconnecting: "Reconnecting", state_disconnecting: "Disconnecting",
    connect: "Connect", disconnect: "Disconnect", cancel: "Cancel",
    tab_home: "Connect", tab_settings: "Settings", tab_logs: "Log",
    down: "Download", up: "Upload", ping: "Ping", exit: "Exit",
    region_unlock: "Region unlock", region_unlock_desc: "Leave from abroad through Psiphon, for Claude, ChatGPT and other services that refuse Iran.",
    region_any: "Fastest", claude_open: "Claude reachable", claude_refused: "Claude refused",
    protocols: "Protocol", auto: "Automatic", auto_desc: "The fastest edge on any protocol, WireGuard first",
    h2_desc: "HTTP/2 over TCP 443, the most reliable", h3_desc: "HTTP/3 over QUIC", wg_desc: "Fastest once warm, with AmneziaWG obfuscation",
    lan_share: "Share on Wi-Fi", lan_share_desc: "Devices on the same Wi-Fi can use Freeport as a proxy. Anyone on that network can use it.", lan_no_wifi: "Not on a network", gool_desc: "Two WireGuard hops, the most cover", tun: "TUN mode", tun_desc: "Every program goes through Freeport, even ones that ignore proxies. Asks for the administrator password.", tun_per_app: "TUN for chosen apps only", tun_per_app_desc: "Only the apps in the Per-app list go through the TUN interface", auto_tune: "Optimize automatically", auto_tune_desc: "Measures each connection and picks the obfuscation that carries the most", per_app: "Per-app", per_app_desc: "Only these apps go through Freeport; the rest of the system stays direct.", open_on_connect: "Open them on connect", add_app: "Add an app…", open: "Open", app_opened: "{app} opened through Freeport", v2ray_desc: "Public configs, tested here, fastest used", psiphon_desc: "Outside Cloudflare, no data cap", tor_desc: "Snowflake, last resort, slow", live: "Live",
    system_proxy: "System proxy", system_proxy_desc: "Point browsers and apps at Freeport while connected",
    proxies: "Local proxies", proxies_desc: "For apps you set up yourself",
    scan: "Scan mode", noize: "Obfuscation", ip: "IP version", ech: "Fetch ECH", ech_desc: "Hide the server name in TLS where possible",
    fragment: "Fragment ClientHello", keepalive: "Keepalive (s)", pinned: "Fixed server (ip:port)", pinned_hint: "empty = scan",
    language: "App language", theme: "Theme", theme_dark: "Dark", theme_light: "Light", theme_system: "System",
    save: "Save", saved: "Saved. Reconnect to use the new settings.", copy: "Copy", copied: "Copied", clear_view: "Follow",
    about: "About", channel: "Telegram channel", source: "GitHub",
    port_busy: "The local proxy ports are taken, probably by another Freeport. Close it, or change the ports in Settings and restart.",
    advanced: "Advanced", network: "Network", appearance: "Appearance",
  },
  fa: {
    state_idle: "وصل نیست", state_preparing: "آماده‌سازی", state_scanning: "جست‌وجوی مسیر",
    state_connecting: "باز کردن تونل", state_unlocking: "در حال انتقال خروجی به خارج", state_connected: "متصل",
    state_reconnecting: "اتصال دوباره", state_disconnecting: "در حال قطع",
    connect: "اتصال", disconnect: "قطع اتصال", cancel: "انصراف",
    tab_home: "اتصال", tab_settings: "تنظیمات", tab_logs: "گزارش",
    down: "دانلود", up: "آپلود", ping: "پینگ", exit: "خروجی",
    region_unlock: "رفع محدودیت منطقه", region_unlock_desc: "خروج از خارج کشور با سایفون، برای Claude، ChatGPT و سرویس‌هایی که ایران را رد می‌کنند.",
    region_any: "سریع‌ترین", claude_open: "Claude در دسترس است", claude_refused: "Claude رد کرد",
    protocols: "پروتکل", auto: "خودکار", auto_desc: "سریع‌ترین سرور در هر پروتکل، اول وایرگارد",
    h2_desc: "HTTP/2 روی TCP 443، مطمئن‌ترین", h3_desc: "HTTP/3 روی QUIC", wg_desc: "بعد از گرم شدن سریع‌ترین، با مبهم‌سازی AmneziaWG",
    lan_share: "اشتراک روی وای‌فای", lan_share_desc: "دستگاه‌های روی همین وای‌فای می‌توانند از فری‌پورت به‌عنوان پروکسی استفاده کنند. هر کسی روی این شبکه به آن دسترسی دارد.", lan_no_wifi: "به شبکه وصل نیست", gool_desc: "دو لایه وایرگارد، بیشترین پوشش", tun: "حالت TUN", tun_desc: "همه‌ی برنامه‌ها، حتی آن‌هایی که پروکسی را نادیده می‌گیرند، از فری‌پورت رد می‌شوند. رمز مدیر سیستم را می‌خواهد.", tun_per_app: "TUN فقط برای برنامه‌های انتخابی", tun_per_app_desc: "فقط برنامه‌های فهرست «برنامه‌های انتخابی» از TUN رد می‌شوند", auto_tune: "بهینه‌سازی خودکار", auto_tune_desc: "سرعت هر اتصال را می‌سنجد و پوششی را انتخاب می‌کند که بیشترین سرعت را دارد", per_app: "برنامه‌های انتخابی", per_app_desc: "فقط این برنامه‌ها از فری‌پورت رد می‌شوند؛ بقیه سیستم مستقیم می‌ماند.", open_on_connect: "باز کردن پس از اتصال", add_app: "افزودن برنامه…", open: "باز کن", app_opened: "{app} از طریق فری‌پورت باز شد", v2ray_desc: "کانفیگ‌های عمومی، تست‌شده روی همین شبکه، سریع‌ترین", psiphon_desc: "خارج از کلودفلر، بدون سقف حجم", tor_desc: "اسنوفلیک، آخرین راه، کند", live: "فعال",
    system_proxy: "پروکسی سیستم", system_proxy_desc: "مرورگرها و برنامه‌ها هنگام اتصال از فری‌پورت رد شوند",
    proxies: "پروکسی‌های محلی", proxies_desc: "برای برنامه‌هایی که خودتان تنظیم می‌کنید",
    scan: "حالت جست‌وجو", noize: "پنهان‌سازی", ip: "نسخه IP", ech: "دریافت ECH", ech_desc: "پنهان کردن نام سرور در TLS در صورت امکان",
    fragment: "تکه‌تکه کردن ClientHello", keepalive: "Keepalive (ثانیه)", pinned: "سرور ثابت (ip:port)", pinned_hint: "خالی = جست‌وجو",
    language: "زبان برنامه", theme: "پوسته", theme_dark: "تیره", theme_light: "روشن", theme_system: "سیستم",
    save: "ذخیره", saved: "ذخیره شد. برای اعمال، دوباره وصل شوید.", copy: "کپی", copied: "کپی شد", clear_view: "دنبال کردن",
    about: "درباره", channel: "کانال تلگرام", source: "گیت‌هاب",
    port_busy: "پورت‌های پروکسی محلی اشغال است، احتمالاً یک فری‌پورت دیگر باز است. آن را ببندید یا پورت‌ها را در تنظیمات عوض کنید و برنامه را دوباره باز کنید.",
    advanced: "پیشرفته", network: "شبکه", appearance: "ظاهر",
  },
  ckb: {
    state_idle: "پەیوەست نییە", state_preparing: "ئامادەکردن", state_scanning: "گەڕان بۆ ڕێگا",
    state_connecting: "کردنەوەی تونێل", state_unlocking: "گواستنەوەی دەرچوون بۆ دەرەوە", state_connected: "پارێزراو",
    state_reconnecting: "پەیوەندی دووبارە", state_disconnecting: "پچڕاندن",
    connect: "پەیوەست بە", disconnect: "پچڕاندن", cancel: "هەڵوەشاندنەوە",
    tab_home: "پەیوەندی", tab_settings: "ڕێکخستن", tab_logs: "تۆمار",
    down: "داگرتن", up: "بارکردن", ping: "پینگ", exit: "دەرچوون",
    region_unlock: "لابردنی سنووری ناوچە", region_unlock_desc: "دەرچوون لە دەرەوەی وڵات بە سایفۆن، بۆ Claude و ChatGPT و خزمەتگوزارییەکانی تر.",
    region_any: "خێراترین", claude_open: "Claude بەردەستە", claude_refused: "Claude ڕەتی کردەوە",
    protocols: "پرۆتۆکۆل", auto: "خۆکار", auto_desc: "خێراترین سێرڤەر لە هەر پرۆتۆکۆلێک، یەکەم WireGuard",
    h2_desc: "HTTP/2 لەسەر TCP 443، باوەڕپێکراوترین", h3_desc: "HTTP/3 لەسەر QUIC", wg_desc: "دوای گەرمبوون خێراترین، لەگەڵ AmneziaWG",
    lan_share: "هاوبەشکردن لەسەر وای‌فای", lan_share_desc: "ئامێرەکانی سەر هەمان وای‌فای دەتوانن فریپۆرت وەک پرۆکسی بەکاربهێنن. هەر کەسێک لەسەر ئەو تۆڕە دەتوانێت بەکاری بهێنێت.", lan_no_wifi: "پەیوەست نییە بە تۆڕ", gool_desc: "دوو چینی WireGuard، زۆرترین پاراستن", tun: "دۆخی TUN", tun_desc: "هەموو بەرنامەکان، تەنانەت ئەوانەی پرۆکسی پشتگوێ دەخەن، بە فریپۆرتدا دەڕۆن. وشەی نهێنیی بەڕێوەبەر دەخوازێت.", tun_per_app: "TUN تەنها بۆ ئەپە هەڵبژێردراوەکان", tun_per_app_desc: "تەنها ئەپەکانی لیستی ئەپە هەڵبژێردراوەکان بە TUN دا دەڕۆن", auto_tune: "باشترکردنی خۆکار", auto_tune_desc: "خێرایی هەر پەیوەندییەک دەپێوێت و خێراترین پۆشین هەڵدەبژێرێت", per_app: "ئەپە هەڵبژێردراوەکان", per_app_desc: "تەنها ئەم ئەپانە بە فریپۆرتدا دەڕۆن؛ باقی سیستەم ڕاستەوخۆ دەمێنێتەوە.", open_on_connect: "کردنەوە دوای پەیوەندی", add_app: "زیادکردنی ئەپ…", open: "بکەرەوە", app_opened: "{app} بە فریپۆرت کرایەوە", v2ray_desc: "کۆنفیگە گشتییەکان، لێرە تاقیکراونەتەوە، خێراترین", psiphon_desc: "دەرەوەی Cloudflare، بێ سنووری قەبارە", tor_desc: "Snowflake، دوایین ڕێگا، هێواش", live: "چالاک",
    system_proxy: "پرۆکسی سیستەم", system_proxy_desc: "وێبگەڕ و ئەپەکان لە کاتی پەیوەندیدا بە فریپۆرتدا بڕۆن",
    proxies: "پرۆکسی ناوخۆیی", proxies_desc: "بۆ ئەو ئەپانەی خۆت ڕێکیان دەخەیت",
    scan: "شێوازی گەڕان", noize: "شاردنەوە", ip: "وەشانی IP", ech: "وەرگرتنی ECH", ech_desc: "شاردنەوەی ناوی سێرڤەر لە TLS",
    fragment: "پارچەکردنی ClientHello", keepalive: "Keepalive (چرکە)", pinned: "سێرڤەری جێگیر (ip:port)", pinned_hint: "بەتاڵ = گەڕان",
    language: "زمانی ئەپ", theme: "ڕووکار", theme_dark: "تاریک", theme_light: "ڕووناک", theme_system: "سیستەم",
    save: "پاشەکەوت", saved: "پاشەکەوت کرا. بۆ جێبەجێکردن دووبارە پەیوەست بە.", copy: "کۆپی", copied: "کۆپی کرا", clear_view: "شوێنکەوتن",
    about: "دەربارە", channel: "کەناڵی تێلێگرام", source: "گیتهەب",
    port_busy: "پۆرتەکانی پرۆکسی ناوخۆیی گیراون، لەوانەیە فریپۆرتێکی تر کراوە بێت. دایبخە یان پۆرتەکان لە ڕێکخستن بگۆڕە.",
    advanced: "پێشکەوتوو", network: "تۆڕ", appearance: "ڕووکار",
  },
  kmr: {
    state_idle: "Ne girêdayî ye", state_preparing: "Amade dibe", state_scanning: "Rê tê lêgerîn",
    state_connecting: "Tunel vedibe", state_unlocking: "Derketin diçe derve", state_connected: "Girêdayî",
    state_reconnecting: "Dîsa tê girêdan", state_disconnecting: "Tê qutkirin",
    connect: "Girê bide", disconnect: "Qut bike", cancel: "Betal bike",
    tab_home: "Girêdan", tab_settings: "Mîheng", tab_logs: "Tomar",
    down: "Daxistin", up: "Barkirin", ping: "Ping", exit: "Derketin",
    region_unlock: "Rakirina sînorê herêmê", region_unlock_desc: "Bi Psiphon ji derveyî welêt derkeve, ji bo Claude, ChatGPT û xizmetên din.",
    region_any: "Herî bilez", claude_open: "Claude berdest e", claude_refused: "Claude red kir",
    protocols: "Protokol", auto: "Otomatîk", auto_desc: "Servera herî bilez li ser her protokolê, pêşî WireGuard",
    h2_desc: "HTTP/2 li ser TCP 443, herî ewle", h3_desc: "HTTP/3 li ser QUIC", wg_desc: "Piştî germbûnê herî bilez, bi AmneziaWG",
    lan_share: "Li ser Wi-Fi parve bike", lan_share_desc: "Amûrên li ser heman Wi-Fi dikarin Freeport wek proxy bi kar bînin. Her kesê li ser wê torê dikare wê bi kar bîne.", lan_no_wifi: "Ne li ser torê", gool_desc: "Du qatên WireGuard, parastina herî zêde", tun: "Moda TUN", tun_desc: "Hemû bername, tevî yên ku proxy paşguh dikin, di Freeport re diçin. Şîfreya rêveberê dixwaze.", tun_per_app: "TUN tenê ji bo sepanên hilbijartî", tun_per_app_desc: "Tenê sepanên lîsteya sepanên hilbijartî di TUN re diçin", auto_tune: "Başkirina otomatîk", auto_tune_desc: "Leza her girêdanê dipîve û veşartina herî bilez hildibijêre", per_app: "Sepanên hilbijartî", per_app_desc: "Tenê ev sepan di Freeport re diçin; yên din rasterast dimînin.", open_on_connect: "Piştî girêdanê veke", add_app: "Sepanekê zêde bike…", open: "Veke", app_opened: "{app} bi Freeport vebû", v2ray_desc: "Konfîgên giştî, li vir hatine ceribandin, ya herî bilez", psiphon_desc: "Derveyî Cloudflare, bê sînorê daneyan", tor_desc: "Snowflake, rêya dawî, hêdî", live: "Çalak",
    system_proxy: "Proxyya pergalê", system_proxy_desc: "Dema girêdayî, gerok û sepan di Freeport re derbas bibin",
    proxies: "Proxyên herêmî", proxies_desc: "Ji bo sepanên ku tu bi xwe saz dikî",
    scan: "Awayê lêgerînê", noize: "Veşartin", ip: "Guhertoya IP", ech: "ECH bistîne", ech_desc: "Navê serverê di TLS de veşêre",
    fragment: "ClientHello parçe bike", keepalive: "Keepalive (s)", pinned: "Servera sabît (ip:port)", pinned_hint: "vala = lêgerîn",
    language: "Zimanê sepanê", theme: "Rûkar", theme_dark: "Tarî", theme_light: "Ronî", theme_system: "Pergal",
    save: "Tomar bike", saved: "Hat tomarkirin. Ji bo bikaranînê dîsa girê bide.", copy: "Kopî", copied: "Hat kopîkirin", clear_view: "Bişopîne",
    about: "Derbarê", channel: "Kanala Telegramê", source: "GitHub",
    port_busy: "Portên proxyya herêmî girtî ne, belkî Freeportek din vekirî ye. Wê bigire an portan di Mîhengan de biguherîne.",
    advanced: "Pêşketî", network: "Tor", appearance: "Xuyabûn",
  },
};

function t(key) {
  const lang = document.documentElement.lang || "en";
  return (STRINGS[lang] && STRINGS[lang][key]) || STRINGS.en[key] || key;
}

function applyLanguage(lang) {
  const chosen = LANGUAGES[lang] ? lang : "en";
  document.documentElement.lang = chosen;
  document.documentElement.dir = LANGUAGES[chosen].dir;
  document.querySelectorAll("[data-t]").forEach((node) => { node.textContent = t(node.dataset.t); });
  document.querySelectorAll("[data-t-placeholder]").forEach((node) => { node.placeholder = t(node.dataset.tPlaceholder); });
}
