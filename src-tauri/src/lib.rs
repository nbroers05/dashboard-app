// Dashboard-app: alleen een raam om https://nbroers05.github.io/Dashboard/?app=pc.
// Het dashboard zelf (ook de titelbalk) staat op GitHub Pages, niet hier.

use tauri::menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::webview::NewWindowResponse;
use tauri::{AppHandle, Manager, Url, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};
use tauri_plugin_opener::OpenerExt;
use tauri_plugin_window_state::{AppHandleExt, StateFlags};

const START: &str = "https://nbroers05.github.io/Dashboard/?app=pc";
const DASHBOARD: &str = "https://nbroers05.github.io/Dashboard/";
// Spotify-inlog blijft in de app (challenge = de "ben je een mens"-stap bij het inloggen)
const SPOTIFY_HOSTS: [&str; 2] = ["accounts.spotify.com", "challenge.spotify.com"];

// Wat binnen de app blijft; al het andere gaat naar de standaardbrowser.
fn in_app(url: &Url) -> bool {
    let s = url.as_str();
    if s.starts_with(DASHBOARD) || s == "https://nbroers05.github.io/Dashboard" || s == "about:blank" {
        return true;
    }
    url.scheme() == "https" && url.host_str().map_or(false, |h| SPOTIFY_HOSTS.contains(&h))
}

fn open_in_browser(app: &AppHandle, url: &Url) {
    if matches!(url.scheme(), "http" | "https" | "mailto") {
        let _ = app.opener().open_url(url.as_str(), None::<&str>);
    }
}

fn show(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

fn toggle(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        if w.is_visible().unwrap_or(false) && !w.is_minimized().unwrap_or(false) {
            let _ = w.hide();
        } else {
            show(app);
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // Positie, maat, gemaximaliseerd en schermvullend onthouden; zichtbaarheid en rand niet.
    let state = StateFlags::SIZE | StateFlags::POSITION | StateFlags::MAXIMIZED | StateFlags::FULLSCREEN;

    tauri::Builder::default()
        // Eén exemplaar: een tweede start haalt het bestaande venster naar voren.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show(app)))
        .plugin(tauri_plugin_window_state::Builder::default().with_state_flags(state).build())
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, None))
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let handle = app.handle().clone();
            let nav = handle.clone();
            let popup = handle.clone();
            WebviewWindowBuilder::new(app, "main", WebviewUrl::External(START.parse().unwrap()))
                .title("Dashboard")
                .inner_size(1200.0, 900.0)
                .min_inner_size(420.0, 500.0)
                .decorations(false)
                .on_navigation(move |url| {
                    if in_app(url) {
                        return true;
                    }
                    open_in_browser(&nav, url);
                    false
                })
                .on_new_window(move |url, _features| {
                    if in_app(&url) {
                        // Geen tweede venster: gewoon in het hoofdvenster openen.
                        let h = popup.clone();
                        tauri::async_runtime::spawn(async move {
                            if let Some(w) = h.get_webview_window("main") {
                                let _ = w.navigate(url);
                            }
                        });
                    } else {
                        open_in_browser(&popup, &url);
                    }
                    NewWindowResponse::Deny
                })
                .build()?;

            // Systeemvak
            let autostart_on = app.autolaunch().is_enabled().unwrap_or(false);
            let show_i = MenuItem::with_id(app, "show", "Tonen", true, None::<&str>)?;
            let reload_i = MenuItem::with_id(app, "reload", "Opnieuw laden", true, None::<&str>)?;
            let auto_i = CheckMenuItem::with_id(app, "autostart", "Automatisch opstarten", true, autostart_on, None::<&str>)?;
            let sep = PredefinedMenuItem::separator(app)?;
            let quit_i = MenuItem::with_id(app, "quit", "Afsluiten", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show_i, &reload_i, &auto_i, &sep, &quit_i])?;
            let auto_item = auto_i.clone();

            TrayIconBuilder::with_id("tray")
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("Dashboard")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(move |app, event| match event.id.as_ref() {
                    "show" => show(app),
                    "reload" => {
                        if let Some(w) = app.get_webview_window("main") {
                            let _ = w.eval("window.location.reload()");
                        }
                    }
                    "autostart" => {
                        let al = app.autolaunch();
                        let want = !al.is_enabled().unwrap_or(false);
                        let _ = if want { al.enable() } else { al.disable() };
                        let _ = auto_item.set_checked(al.is_enabled().unwrap_or(want));
                    }
                    "quit" => {
                        let _ = app.save_window_state(state);
                        app.exit(0);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                        toggle(tray.app_handle());
                    }
                })
                .build(app)?;
            Ok(())
        })
        // Sluitknop = naar het systeemvak; afsluiten kan alleen via het menu daar.
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())
        .expect("Dashboard-app kon niet starten");
}
