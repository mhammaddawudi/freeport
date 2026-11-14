//! One public V2Ray config, read from its share link (`vless://`, `vmess://`,
//! `trojan://`, `ss://`) and turned into an Xray outbound. The same rules as
//! the Android app's ShareLink: every field Xray would reject is checked
//! here, since one bad outbound makes Xray refuse a whole test batch.

use std::collections::HashMap;

use serde_json::{json, Map, Value};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Vless,
    Vmess,
    Trojan,
    Shadowsocks,
}

impl Kind {
    fn wire(self) -> &'static str {
        match self {
            Kind::Vless => "vless",
            Kind::Vmess => "vmess",
            Kind::Trojan => "trojan",
            Kind::Shadowsocks => "shadowsocks",
        }
    }
}

#[derive(Debug, Clone)]
pub struct ShareLink {
    pub kind: Kind,
    pub host: String,
    pub port: u16,
    /// UUID for VLESS and VMess, password for Trojan and Shadowsocks.
    pub secret: String,
    /// Transport and security parameters, by their share-link names.
    pub params: HashMap<String, String>,
    pub raw: String,
}

const NETWORKS: [&str; 6] = ["tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp"];
const SECURITIES: [&str; 3] = ["none", "tls", "reality"];
const XHTTP_MODES: [&str; 4] = ["auto", "packet-up", "stream-up", "stream-one"];
const VMESS_CIPHERS: [&str; 5] = ["auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"];
const FINGERPRINTS: [&str; 10] = ["chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"];
const SS_METHODS: [&str; 9] = [
    "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
    "xchacha20-poly1305", "xchacha20-ietf-poly1305",
    "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
];

impl ShareLink {
    fn param(&self, key: &str) -> Option<&str> {
        self.params.get(key).map(String::as_str).filter(|v| !v.is_empty())
    }

    pub fn network(&self) -> &str {
        match self.param("type").unwrap_or("tcp") {
            "raw" => "tcp",
            other => other,
        }
    }

    pub fn security(&self) -> &str {
        match self.param("security") {
            None | Some("false") => "none",
            Some(other) => other,
        }
    }

    /// Two links that reach the same server the same way are one config.
    pub fn key(&self) -> String {
        format!(
            "{:?}|{}|{}|{}|{}|{}|{}|{}|{}",
            self.kind,
            self.host.to_lowercase(),
            self.port,
            self.secret,
            self.network(),
            self.security(),
            self.param("path").unwrap_or(""),
            self.param("sni").unwrap_or(""),
            self.param("serviceName").unwrap_or(""),
        )
    }

    /// A short name for the log: protocol, transport and server.
    pub fn label(&self) -> String {
        format!("{}/{} {}:{}", self.kind.wire(), self.network(), self.host, self.port)
    }

    /// The Xray outbound for this config, tagged `tag`.
    pub fn outbound(&self, tag: &str) -> Value {
        let server = |extra: Value| {
            let mut object = json!({ "address": self.host, "port": self.port });
            if let (Some(target), Value::Object(more)) = (object.as_object_mut(), extra) {
                target.extend(more);
            }
            object
        };
        let settings = match self.kind {
            Kind::Vless => {
                let mut user = json!({ "id": self.secret, "encryption": self.param("encryption").unwrap_or("none") });
                if let Some(flow) = self.param("flow") {
                    user["flow"] = json!(flow);
                }
                json!({ "vnext": [server(json!({ "users": [user] }))] })
            }
            Kind::Vmess => {
                let cipher = self.param("scy").filter(|c| VMESS_CIPHERS.contains(c)).unwrap_or("auto");
                let aid: u32 = self.param("aid").and_then(|a| a.parse().ok()).unwrap_or(0);
                json!({ "vnext": [server(json!({ "users": [{ "id": self.secret, "alterId": aid, "security": cipher }] }))] })
            }
            Kind::Trojan => json!({ "servers": [server(json!({ "password": self.secret }))] }),
            Kind::Shadowsocks => json!({ "servers": [server(json!({ "method": self.param("method").unwrap_or(""), "password": self.secret }))] }),
        };
        json!({ "tag": tag, "protocol": self.kind.wire(), "settings": settings, "streamSettings": self.stream() })
    }

    fn stream(&self) -> Value {
        let network = self.network();
        let mut stream = Map::new();
        stream.insert("network".into(), json!(if network == "tcp" { "raw" } else { network }));
        stream.insert("security".into(), json!(self.security()));
        let host = self.param("host");
        let path = self.param("path").unwrap_or("/");
        let with_host = |mut object: Value| {
            if let Some(h) = host {
                object["host"] = json!(h);
            }
            object
        };
        match network {
            "ws" => {
                stream.insert("wsSettings".into(), with_host(json!({ "path": path })));
            }
            "httpupgrade" => {
                stream.insert("httpupgradeSettings".into(), with_host(json!({ "path": path })));
            }
            "grpc" => {
                stream.insert(
                    "grpcSettings".into(),
                    json!({ "serviceName": self.param("serviceName").unwrap_or(""), "multiMode": self.param("mode") == Some("multi") }),
                );
            }
            "xhttp" => {
                let mode = self.param("mode").filter(|m| XHTTP_MODES.contains(m)).unwrap_or("auto");
                let mut settings = with_host(json!({ "path": path, "mode": mode }));
                if let Some(extra) = self.param("extra").and_then(|e| serde_json::from_str::<Value>(e).ok()).filter(Value::is_object) {
                    settings["extra"] = extra;
                }
                stream.insert("xhttpSettings".into(), settings);
            }
            "tcp" if self.param("headerType") == Some("http") => {
                let mut request = json!({ "path": [path] });
                if let Some(h) = host {
                    request["headers"] = json!({ "Host": h.split(',').collect::<Vec<_>>() });
                }
                stream.insert("rawSettings".into(), json!({ "header": { "type": "http", "request": request } }));
            }
            _ => {}
        }
        let server_name = self.param("sni").map(str::to_string).or_else(|| host.map(|h| h.split(',').next().unwrap_or(h).to_string())).unwrap_or_else(|| self.host.clone());
        let fingerprint = self.param("fp").filter(|f| FINGERPRINTS.contains(f)).unwrap_or("chrome");
        match self.security() {
            // Xray no longer takes allowInsecure; a config that needs it fails its test instead.
            "tls" => {
                let mut tls = json!({ "serverName": server_name, "fingerprint": fingerprint });
                if let Some(alpn) = self.param("alpn") {
                    tls["alpn"] = json!(alpn.split(',').collect::<Vec<_>>());
                }
                stream.insert("tlsSettings".into(), tls);
            }
            "reality" => {
                stream.insert(
                    "realitySettings".into(),
                    json!({
                        "serverName": server_name, "fingerprint": fingerprint,
                        "publicKey": self.param("pbk").unwrap_or(""), "shortId": self.param("sid").unwrap_or(""),
                        "spiderX": self.param("spx").unwrap_or("/"),
                    }),
                );
            }
            _ => {}
        }
        Value::Object(stream)
    }

    fn is_usable(&self) -> bool {
        if self.port == 0 || self.host.is_empty() || self.host.contains(' ') {
            return false;
        }
        if !NETWORKS.contains(&self.network()) || !SECURITIES.contains(&self.security()) {
            return false;
        }
        let secret_ok = match self.kind {
            Kind::Vless | Kind::Vmess => is_uuid(&self.secret),
            Kind::Trojan => !self.secret.is_empty(),
            Kind::Shadowsocks => !self.secret.is_empty() && self.param("method").is_some_and(|m| SS_METHODS.contains(&m)),
        };
        let reality_ok = self.security() != "reality"
            || (self.param("pbk").is_some_and(|k| k.len() == 43 && k.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_'))
                && self.param("sid").unwrap_or("").len() <= 16
                && self.param("sid").unwrap_or("").bytes().all(|b| b.is_ascii_hexdigit()));
        let flow_ok = self.kind != Kind::Vless || self.param("flow").is_none_or(|f| f == "xtls-rprx-vision");
        secret_ok && reality_ok && flow_ok
    }
}

fn is_uuid(text: &str) -> bool {
    let parts: Vec<&str> = text.split('-').collect();
    parts.len() == 5
        && parts.iter().zip([8, 4, 4, 4, 12]).all(|(part, len)| part.len() == len && part.bytes().all(|b| b.is_ascii_hexdigit()))
}

/// Every config in a subscription body: plain lines, or the whole of it in base64.
pub fn parse_all(body: &str) -> Vec<ShareLink> {
    let text = if body.contains("://") {
        body.to_string()
    } else {
        match decode_base64(&body.trim().replace(['\n', '\r'], "")) {
            Some(text) => text,
            None => return Vec::new(),
        }
    };
    text.lines().filter_map(|line| parse(line.trim())).collect()
}

pub fn parse(line: &str) -> Option<ShareLink> {
    let link = if let Some(rest) = line.strip_prefix("vless://") {
        standard(line, rest, Kind::Vless)
    } else if let Some(rest) = line.strip_prefix("trojan://") {
        standard(line, rest, Kind::Trojan)
    } else if let Some(rest) = line.strip_prefix("vmess://") {
        vmess(line, rest)
    } else if let Some(rest) = line.strip_prefix("ss://") {
        shadowsocks(line, rest)
    } else {
        None
    }?;
    link.is_usable().then_some(link)
}

/// `scheme://secret@host:port?params#name`: VLESS and Trojan.
fn standard(line: &str, rest: &str, kind: Kind) -> Option<ShareLink> {
    let rest = rest.split('#').next()?;
    let (main, query) = rest.split_once('?').unwrap_or((rest, ""));
    let main = main.trim_end_matches('/');
    let (secret, address) = main.rsplit_once('@')?;
    let (host, port) = split_host_port(address)?;
    Some(ShareLink { kind, host, port, secret: percent_decode(secret), params: query_map(query), raw: line.to_string() })
}

fn vmess(line: &str, rest: &str) -> Option<ShareLink> {
    let json: Value = serde_json::from_str(&decode_base64(rest.split('#').next()?)?).ok()?;
    let text = |key: &str| match &json[key] {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        _ => String::new(),
    };
    let net = text("net");
    let mut params = HashMap::new();
    params.insert("type".to_string(), if net.is_empty() { "tcp".into() } else { net.clone() });
    params.insert("security".to_string(), if text("tls") == "tls" { "tls".into() } else { "none".into() });
    params.insert("headerType".to_string(), text("type"));
    for key in ["host", "path", "sni", "alpn", "fp", "aid", "scy"] {
        let value = text(key);
        if !value.is_empty() {
            params.insert(key.to_string(), value);
        }
    }
    if net == "grpc" {
        params.insert("serviceName".to_string(), text("path"));
    }
    Some(ShareLink { kind: Kind::Vmess, host: text("add"), port: text("port").parse().ok()?, secret: text("id"), params, raw: line.to_string() })
}

/// SIP002 (`ss://base64(method:password)@host:port`) or the older all-base64 form.
fn shadowsocks(line: &str, rest: &str) -> Option<ShareLink> {
    let body = rest.split('#').next()?;
    if body.contains("plugin=") {
        return None;
    }
    let full = if body.contains('@') { body.to_string() } else { decode_base64(body.split('?').next()?)? };
    let (credentials, address) = full.rsplit_once('@')?;
    let credentials = decode_base64(credentials).filter(|c| c.contains(':')).unwrap_or_else(|| percent_decode(credentials));
    let (method, password) = credentials.split_once(':')?;
    let address = address.split(['?', '/']).next()?;
    let (host, port) = split_host_port(address)?;
    let params = HashMap::from([("method".to_string(), method.to_lowercase())]);
    Some(ShareLink { kind: Kind::Shadowsocks, host, port, secret: password.to_string(), params, raw: line.to_string() })
}

fn split_host_port(address: &str) -> Option<(String, u16)> {
    let (host, port) = address.rsplit_once(':')?;
    Some((host.trim_matches(['[', ']']).to_string(), port.parse().ok()?))
}

fn query_map(query: &str) -> HashMap<String, String> {
    query.split('&').filter_map(|pair| pair.split_once('=')).map(|(k, v)| (percent_decode(k), percent_decode(v))).collect()
}

/// `%XX` escapes; a `+` stays a plus, as in the links' base64 keys.
fn percent_decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(value) = u8::from_str_radix(&text[i + 1..i + 3], 16) {
                out.push(value);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8(out).unwrap_or_else(|_| text.to_string())
}

/// Standard or URL-safe base64, padded or not.
pub fn decode_base64(text: &str) -> Option<String> {
    let mut bits: u32 = 0;
    let mut count = 0;
    let mut out = Vec::with_capacity(text.len() * 3 / 4);
    for byte in text.trim().bytes() {
        let value = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'+' | b'-' => 62,
            b'/' | b'_' => 63,
            b'=' => break,
            _ => return None,
        };
        bits = (bits << 6) | u32::from(value);
        count += 6;
        if count >= 8 {
            count -= 8;
            out.push((bits >> count) as u8);
            bits &= (1 << count) - 1;
        }
    }
    String::from_utf8(out).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_vless_reality() {
        let line = "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality&pbk=abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ&sid=ab12&sni=www.apple.com&flow=xtls-rprx-vision#name";
        let link = parse(line).expect("parsed");
        assert_eq!(link.port, 443);
        let out = link.outbound("out0");
        assert_eq!(out["streamSettings"]["network"], "raw");
        assert_eq!(out["streamSettings"]["realitySettings"]["serverName"], "www.apple.com");
        assert_eq!(out["settings"]["vnext"][0]["users"][0]["flow"], "xtls-rprx-vision");
    }

    #[test]
    fn reads_vmess_and_shadowsocks() {
        let vmess = r#"{"v":"2","add":"1.2.3.4","port":"8080","id":"11111111-2222-3333-4444-555555555555","net":"ws","path":"/x","host":"h.com","tls":"tls"}"#;
        let encoded = encode(vmess);
        let link = parse(&format!("vmess://{encoded}")).expect("vmess");
        assert_eq!(link.outbound("o")["streamSettings"]["wsSettings"]["host"], "h.com");
        let ss = format!("ss://{}@5.6.7.8:8388#n", encode("aes-256-gcm:secret"));
        let link = parse(&ss).expect("ss");
        assert_eq!(link.outbound("o")["settings"]["servers"][0]["method"], "aes-256-gcm");
    }

    #[test]
    fn refuses_what_xray_would() {
        assert!(parse("vless://not-a-uuid@a.com:443?type=tcp").is_none());
        assert!(parse("vless://11111111-2222-3333-4444-555555555555@a.com:443?type=kcp").is_none());
        assert!(parse("ss://YWVzLTEyOC1jZmI6cGFzcw@a.com:1").is_none());
    }

    fn encode(text: &str) -> String {
        const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let bytes = text.as_bytes();
        let mut out = String::new();
        for chunk in bytes.chunks(3) {
            let n = chunk.iter().enumerate().fold(0u32, |acc, (i, b)| acc | (u32::from(*b) << (16 - 8 * i)));
            for i in 0..=chunk.len() {
                out.push(T[((n >> (18 - 6 * i)) & 63) as usize] as char);
            }
        }
        out
    }
}
