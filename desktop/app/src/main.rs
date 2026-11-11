//! Freeport for Windows and macOS: the engine in process, a window that
//! shows it, and a tray icon that keeps it running with the window closed.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod apps;
mod awake;
mod session;
mod view;

use std::sync::Arc;

use freeport_engine::engine::Phase;
use freeport_engine::logbus;
use freeport_engine::settings::Settings;
use serde::Serialize;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, RunEvent, State, WindowEvent};

use crate::session::Session;
use crate::view::{LogView, StatusView, Traffic};

/// Links the window may open; anything else is refused.
const LINKS: [&str; 3] = [
    "https://t.me/Freeport_VPN",
    "https://github.com/mhammaddawudi",
    "https://github.com/mhammaddawudi/freeport/releases",
];

type Shared = Arc<Session>;

#[derive(Serialize)]
struct Snapshot {
    status: StatusView,
    settings: Settings,
    version: &'static str,
    platform: &'static str,
    bind_error: Option<String>,
}

#[tauri::command]
fn snapshot(session: State<'_, Shared>) -> Snapshot {
    let status = session
        .engine()
        .map(|engine| StatusView::of(&engine.watch().borrow()))
        .unwrap_or_else(|_| StatusView::of(&Default::default()));
    Snapshot {
        status,
        settings: session.settings(),
        version: env!("CARGO_PKG_VERSION"),
        platform: std::env::consts::OS,
        bind_error: session.bind_error(),
    }
}

#[tauri::command]
async fn connect(session: State<'_, Shared>) -> Result<(), String> {
    session.connect().await.map_err(|error| format!("{error:#}"))
}

#[tauri::command]
async fn disconnect(session: State<'_, Shared>) -> Result<(), String> {
    session.disconnect().await.map_err(|error| format!("{error:#}"))
}

#[tauri::command]
async fn save_settings(app: AppHandle, session: State<'_, Shared>, settings: Settings) -> Result<Settings, String> {
    let started = session.save(settings).await.map_err(|error| format!("{error:#}"))?;
    if started {
        watch_status(&app, session.inner().clone());
    }
    Ok(session.settings())
}

#[tauri::command]
fn traffic(session: State<'_, Shared>) -> Traffic {
    session.traffic()
}

/// The newest `limit` log lines.
#[tauri::command]
fn logs(limit: usize) -> Vec<LogView> {
    let lines = logbus::snapshot();
    let skip = lines.len().saturating_sub(limit.min(2_000));
    lines.iter().enumerate().skip(skip).map(|(index, line)| LogView::of(index, line)).collect()
}

#[tauri::command]
fn open_link(url: String) -> Result<(), String> {
    if !LINKS.contains(&url.as_str()) {
        return Err("that link is not one Freeport opens".into());
    }
    open_in_browser(&url).map_err(|error| error.to_string())
}

fn open_in_browser(url: &str) -> std::io::Result<()> {
    #[cfg(target_os = "macos")]
    let mut command = std::process::Command::new("open");
    #[cfg(windows)]
    let mut command = {
        use std::os::windows::process::CommandExt;
        let mut command = std::process::Command::new("rundll32");
        command.arg("url.dll,FileProtocolHandler").creation_flags(0x0800_0000);
        command
    };
    #[cfg(not(any(target_os = "macos", windows)))]
    let mut command = std::process::Command::new("xdg-open");
    command.arg(url).spawn().map(|_| ())
}

fn open_chosen_apps(session: &Shared) {
    let settings = session.settings();
    if !settings.open_apps_on_connect {
        return;
    }
    let (socks, http) = (settings.socks_port, settings.http_port);
    for path in settings.proxied_apps {
        if let Err(error) = apps::open(&path, socks, http) {
            log::warn!(target: "freeport", "[-] {error:#}");
        }
    }
}

#[tauri::command]
async fn installed_apps() -> Vec<apps::App> {
    tauri::async_runtime::spawn_blocking(apps::installed).await.unwrap_or_default()
}

#[tauri::command]
async fn open_app(session: State<'_, Shared>, path: String) -> Result<(), String> {
    let settings = session.settings();
    let (socks, http) = (settings.socks_port, settings.http_port);
    tauri::async_runtime::spawn_blocking(move || apps::open(&path, socks, http))
        .await
        .map_err(|error| error.to_string())?
        .map_err(|error| format!("{error:#}"))
}

/// Sends every status change to the window, and lets the session follow it.
fn watch_status(app: &AppHandle, session: Shared) {
    let Ok(engine) = session.engine() else { return };
    let mut status = engine.watch();
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        // Chosen apps open once per session, not again after each reconnect.
        let mut opened = false;
        loop {
            let (view, phase) = {
                let current = status.borrow_and_update();
                session.follow(&current.phase);
                (StatusView::of(&current), current.phase.clone())
            };
            match phase {
                Phase::Connected if !opened => {
                    opened = true;
                    open_chosen_apps(&session);
                }
                Phase::Idle => opened = false,
                _ => {}
            }
            update_tray(&app, &view);
            let _ = app.emit("status", view);
            if status.changed().await.is_err() {
                break;
            }
        }
    });
}

fn show_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, "show", "Show Freeport", true, None::<&str>)?;
    let toggle = MenuItem::with_id(app, "toggle", "Connect", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &toggle, &quit])?;
    app.manage(toggle);
    let icon = tray_icon(false)?;
    TrayIconBuilder::with_id("main")
        .icon(icon)
        .tooltip("Freeport")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => show_window(app),
            "toggle" => {
                let session = app.state::<Shared>().inner().clone();
                tauri::async_runtime::spawn(async move {
                    let engaged = session
                        .engine()
                        .map(|engine| engine.watch().borrow().phase.is_engaged())
                        .unwrap_or(false);
                    let _ = if engaged { session.disconnect().await } else { session.connect().await };
                });
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_window(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

/// Grey while off, the New Season aurora once connected.
fn tray_icon(connected: bool) -> tauri::Result<tauri::image::Image<'static>> {
    let bytes: &'static [u8] = match connected {
        true => include_bytes!("../icons/tray-on.png"),
        false => include_bytes!("../icons/tray-off.png"),
    };
    tauri::image::Image::from_bytes(bytes)
}

fn update_tray(app: &AppHandle, view: &StatusView) {
    if let Some(toggle) = app.try_state::<MenuItem<tauri::Wry>>() {
        let _ = toggle.set_text(if view.phase == "idle" { "Connect" } else { "Disconnect" });
    }
    if let Some(tray) = app.tray_by_id("main") {
        let tip = match view.phase {
            "connected" => format!("Freeport · {}", view.protocol_name.unwrap_or("connected")),
            "idle" => "Freeport · off".to_string(),
            other => format!("Freeport · {other}"),
        };
        let _ = tray.set_tooltip(Some(tip));
        if let Ok(icon) = tray_icon(view.phase == "connected") {
            let _ = tray.set_icon(Some(icon));
        }
    }
}

/// Sizes the window to the screen it opens on, so it never runs past the
/// taskbar or the Dock on a small display.
fn fit_window(app: &AppHandle) {
    let Some(window) = app.get_webview_window("main") else { return };
    let Ok(Some(monitor)) = window.current_monitor().or_else(|_| window.primary_monitor()) else { return };
    let scale = monitor.scale_factor();
    let area = monitor.work_area().size.to_logical::<f64>(scale);
    let height = (area.height * 0.92).clamp(560.0, 820.0);
    let width = (height * 0.54).clamp(380.0, 440.0).min(area.width * 0.95);
    let _ = window.set_min_size(Some(tauri::LogicalSize::new(width.min(380.0), height.min(560.0))));
    let _ = window.set_size(tauri::LogicalSize::new(width, height));
    let _ = window.center();
}

fn main() {
    logbus::install(false, false);
    awake::hold();
    let session: Shared = Arc::new(tauri::async_runtime::block_on(Session::open()));

    let app = tauri::Builder::default()
        .manage(session.clone())
        .invoke_handler(tauri::generate_handler![snapshot, connect, disconnect, save_settings, traffic, logs, open_link, installed_apps, open_app])
        .setup(move |app| {
            build_tray(app.handle())?;
            fit_window(app.handle());
            watch_status(app.handle(), session.clone());
            Ok(())
        })
        .on_window_event(|window, event| {
            // Closing the window keeps Freeport running in the tray.
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .build(tauri::generate_context!())
        .expect("Freeport could not start its window");

    app.run(|handle, event| match event {
        RunEvent::ExitRequested { .. } => {
            // Psiphon and the system proxy must not outlive the app.
            let session = handle.state::<Shared>().inner().clone();
            tauri::async_runtime::block_on(async move {
                let _ = session.disconnect().await;
                session.release_proxy();
            });
        }
        #[cfg(target_os = "macos")]
        RunEvent::Reopen { .. } => show_window(handle),
        _ => {}
    });
}
