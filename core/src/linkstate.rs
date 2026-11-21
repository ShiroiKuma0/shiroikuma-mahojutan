// Fork: what this device's Wi-Fi link looks like right now, and a way to shake it loose.
//
// It exists because of one afternoon (白い熊, 2026-11-21): phone → PC transfers ran at 13 MB/s
// while PC → phone ran at 49. Nothing in the app was slow. The access point had been sending to
// this PC on a 20 MHz channel for forty hours — 172 Mbit/s — while the PC transmitted on 160 MHz
// at 1150. A plain `nc` stream showed the same ceiling, and reassociating the Wi-Fi restored it.
//
// A transfer cannot see that from inside its own socket, and the phone cannot see it at all: the
// narrow hop was access point → PC, which only the PC's radio knows. So the PC answers when a
// paired device asks (paired.rs, REQUEST_LINK_STATUS), and reconnects when told to
// (REQUEST_REASSOCIATE). The asking, the judging and the dialog are the phone's — LinkDoctor.kt.
//
// Linux only. Everything is read with `iw` and `ip`, which need no privilege, and the reconnect
// is `nmcli connection up` on the connection that is already active — the same thing clicking
// the network again in the tray does.

use std::net::IpAddr;

/// One direction of the link, as the driver last reported it.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Rate {
    /// Megabits per second. Zero when the driver gave none.
    pub mbps: f64,
    /// Channel width of that frame in MHz. `iw` leaves the width out for 20 MHz.
    pub mhz: u32,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct LinkState {
    /// False when the connection does not run over Wi-Fi, or nothing could be read.
    pub wifi: bool,
    pub iface: String,
    /// The width the network itself runs on, as this device joined it.
    pub channel_mhz: u32,
    /// Access point → this device.
    pub rx: Rate,
    /// This device → access point.
    pub tx: Rate,
    /// dBm; zero when unknown.
    pub signal: i32,
}

impl LinkState {
    /// `key=value` lines. Text rather than fixed fields so a later build can add one without
    /// the other end reading it out of the wrong offset; unknown keys are ignored there.
    pub fn to_wire(&self) -> String {
        format!(
            "wifi={}\niface={}\nchannel_mhz={}\nrx_mbps={:.1}\nrx_mhz={}\ntx_mbps={:.1}\ntx_mhz={}\nsignal={}\n",
            if self.wifi { 1 } else { 0 },
            self.iface,
            self.channel_mhz,
            self.rx.mbps,
            self.rx.mhz,
            self.tx.mbps,
            self.tx.mhz,
            self.signal
        )
    }

    /// One line for a log.
    pub fn describe(&self) -> String {
        if !self.wifi {
            return "not on Wi-Fi".to_string();
        }
        format!(
            "{}: network {} MHz, receiving {:.0} Mbit/s on {} MHz, sending {:.0} Mbit/s on {} MHz, signal {} dBm",
            self.iface,
            self.channel_mhz,
            self.rx.mbps,
            self.rx.mhz,
            self.tx.mbps,
            self.tx.mhz,
            self.signal
        )
    }
}

/// `1152.8 MBit/s 160MHz HE-MCS 5 HE-NSS 2 …` → rate and width. A line with no width is 20 MHz:
/// that is how `iw` prints it, and it is exactly the case this module was written to catch.
pub fn parse_bitrate(value: &str) -> Rate {
    let mut words = value.split_whitespace();
    let mbps = words.next().and_then(|w| w.parse::<f64>().ok()).unwrap_or(0.0);
    let mhz = value
        .split_whitespace()
        .find_map(|w| w.strip_suffix("MHz").and_then(|n| n.parse::<u32>().ok()))
        .unwrap_or(20);
    Rate { mbps, mhz }
}

/// The interesting lines of `iw dev <iface> link`.
pub fn parse_link(output: &str) -> Option<(Rate, Rate, i32)> {
    if !output.contains("Connected to") {
        return None;
    }
    let mut rx = Rate::default();
    let mut tx = Rate::default();
    let mut signal = 0;
    for line in output.lines() {
        let line = line.trim();
        if let Some(v) = line.strip_prefix("rx bitrate:") {
            rx = parse_bitrate(v);
        } else if let Some(v) = line.strip_prefix("tx bitrate:") {
            tx = parse_bitrate(v);
        } else if let Some(v) = line.strip_prefix("signal:") {
            signal = v
                .split_whitespace()
                .next()
                .and_then(|w| w.parse::<i32>().ok())
                .unwrap_or(0);
        }
    }
    Some((rx, tx, signal))
}

/// `channel 60 (5300 MHz), width: 160 MHz, center1: 5250 MHz` out of `iw dev <iface> info`.
pub fn parse_channel_width(output: &str) -> u32 {
    for line in output.lines() {
        if let Some(rest) = line.split("width:").nth(1) {
            if let Some(n) = rest.split_whitespace().next().and_then(|w| w.parse::<u32>().ok()) {
                return n;
            }
        }
    }
    0
}

/// The interface that carries `local_ip`, out of `ip -o -4 addr show`.
pub fn parse_iface_for(output: &str, local_ip: &str) -> Option<String> {
    for line in output.lines() {
        let mut words = line.split_whitespace();
        let _index = words.next();
        let iface = words.next()?;
        while let Some(word) = words.next() {
            if word == "inet" {
                if let Some(addr) = words.next() {
                    if addr.split('/').next() == Some(local_ip) {
                        return Some(iface.to_string());
                    }
                }
            }
        }
    }
    None
}

#[cfg(target_os = "linux")]
fn run(program: &str, args: &[&str]) -> Option<String> {
    let output = std::process::Command::new(program).args(args).output().ok()?;
    if !output.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// The link the connection bound to `local_ip` runs over.
///
/// Sampled three times a little apart, keeping the widest reading of each direction: the
/// driver reports the *last* frame, and a beacon or an ACK arriving between two data frames
/// would otherwise read as a narrow link that is not one.
#[cfg(target_os = "linux")]
pub fn read(local_ip: IpAddr) -> LinkState {
    let Some(addresses) = run("ip", &["-o", "-4", "addr", "show"]) else {
        return LinkState::default();
    };
    let Some(iface) = parse_iface_for(&addresses, &local_ip.to_string()) else {
        return LinkState::default();
    };
    if !std::path::Path::new(&format!("/sys/class/net/{}/wireless", iface)).exists()
        && !std::path::Path::new(&format!("/sys/class/net/{}/phy80211", iface)).exists()
    {
        return LinkState { iface, ..LinkState::default() };
    }
    let channel_mhz = run("iw", &["dev", &iface, "info"])
        .map(|o| parse_channel_width(&o))
        .unwrap_or(0);
    let mut state = LinkState { wifi: false, iface: iface.clone(), channel_mhz, ..LinkState::default() };
    for sample in 0..3 {
        if sample > 0 {
            std::thread::sleep(std::time::Duration::from_millis(120));
        }
        let Some((rx, tx, signal)) = run("iw", &["dev", &iface, "link"]).and_then(|o| parse_link(&o))
        else {
            continue;
        };
        state.wifi = true;
        state.signal = signal;
        if rx.mhz > state.rx.mhz || (rx.mhz == state.rx.mhz && rx.mbps > state.rx.mbps) {
            state.rx = rx;
        }
        if tx.mhz > state.tx.mhz || (tx.mhz == state.tx.mhz && tx.mbps > state.tx.mbps) {
            state.tx = tx;
        }
    }
    state
}

#[cfg(not(target_os = "linux"))]
pub fn read(_local_ip: IpAddr) -> LinkState {
    LinkState::default()
}

/// Reconnects `iface` to the network it is on. Blocks until NetworkManager says it is up
/// again — a dozen seconds, most of it the scan — so call it off the async runtime.
#[cfg(target_os = "linux")]
pub fn reassociate(iface: &str) -> Result<(), String> {
    let active = run("nmcli", &["-t", "-f", "UUID,DEVICE", "connection", "show", "--active"])
        .ok_or_else(|| "NetworkManager (nmcli) did not answer.".to_string())?;
    let uuid = active
        .lines()
        .find_map(|line| {
            let (uuid, device) = line.split_once(':')?;
            (device == iface).then(|| uuid.to_string())
        })
        .ok_or_else(|| format!("No active connection was found on {}.", iface))?;
    let output = std::process::Command::new("nmcli")
        .args(["--wait", "45", "connection", "up", "uuid", &uuid, "ifname", iface])
        .output()
        .map_err(|e| format!("Could not run nmcli: {}", e))?;
    if output.status.success() {
        Ok(())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
    }
}

#[cfg(not(target_os = "linux"))]
pub fn reassociate(_iface: &str) -> Result<(), String> {
    Err("Reconnecting the Wi-Fi from here is only built for Linux.".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    // Verbatim from sktx on 2026-11-21, while the access point was feeding it at 20 MHz.
    const NARROW: &str = "Connected to 08:7b:12:a6:27:3d (on wlp132s0f0)
\tSSID: kmwfhk6
\tfreq: 5300.0
\tsignal: -70 dBm
\trx bitrate: 172.0 MBit/s HE-MCS 7 HE-NSS 2 HE-GI 0 HE-DCM 0
\ttx bitrate: 1152.8 MBit/s 160MHz HE-MCS 5 HE-NSS 2 HE-GI 0 HE-DCM 0
\tbss flags: short-slot-time";

    #[test]
    fn a_rate_without_a_width_is_twenty_megahertz() {
        let (rx, tx, signal) = parse_link(NARROW).unwrap();
        assert_eq!(rx, Rate { mbps: 172.0, mhz: 20 });
        assert_eq!(tx, Rate { mbps: 1152.8, mhz: 160 });
        assert_eq!(signal, -70);
    }

    #[test]
    fn a_link_that_is_down_reads_as_nothing() {
        assert!(parse_link("Not connected.").is_none());
    }

    #[test]
    fn the_network_width_comes_from_the_info_line() {
        let info = "Interface wlp132s0f0\n\ttype managed\n\tchannel 60 (5300 MHz), width: 160 MHz, center1: 5250 MHz\n";
        assert_eq!(parse_channel_width(info), 160);
        assert_eq!(parse_channel_width("Interface lo"), 0);
    }

    #[test]
    fn the_interface_is_the_one_holding_the_address() {
        let out = "1: lo    inet 127.0.0.1/8 scope host lo\n4: wlp132s0f0    inet 192.168.1.37/24 brd 192.168.1.255 scope global dynamic wlp132s0f0\n";
        assert_eq!(parse_iface_for(out, "192.168.1.37").as_deref(), Some("wlp132s0f0"));
        assert_eq!(parse_iface_for(out, "192.168.1.3"), None);
    }

    /// Reads this machine's real link. Ignored: it needs `iw`, `ip` and a Wi-Fi connection.
    /// `LINK_IP=192.168.1.37 cargo test -p flying-carpet-core reads_this_machine -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn reads_this_machine() {
        let ip: IpAddr = std::env::var("LINK_IP").expect("set LINK_IP").parse().unwrap();
        let state = read(ip);
        println!("{}\n{}", state.describe(), state.to_wire());
        assert!(state.wifi);
    }

    #[test]
    fn the_wire_form_carries_every_field() {
        let state = LinkState {
            wifi: true,
            iface: "wlp132s0f0".into(),
            channel_mhz: 160,
            rx: Rate { mbps: 172.0, mhz: 20 },
            tx: Rate { mbps: 1152.8, mhz: 160 },
            signal: -70,
        };
        let wire = state.to_wire();
        for line in ["wifi=1", "channel_mhz=160", "rx_mbps=172.0", "rx_mhz=20", "tx_mhz=160", "signal=-70"] {
            assert!(wire.lines().any(|l| l == line), "missing {} in {}", line, wire);
        }
    }
}
