use crate::error::{fc_error, FCError};
use crate::utils::{run_command, run_command_async};
use crate::{InterfaceInfo, Mode, Peer, PeerResource, WiFiInterface, UI};
use tokio::task;

// stub
pub struct WindowsHotspot {
    _inner: (),
}

pub fn is_hosting(peer: &Peer, mode: &Mode) -> bool {
    match peer {
        // Android hosts for us, not the other way round (fork, 白い熊 2026-08-11). Our AP is a
        // NetworkManager profile and NM 1.46 has no channel-width setting, so it comes up 20 MHz
        // whatever band we ask for -- measured at 2437 MHz and, after the band fix, again at
        // 5745 MHz, both 20 MHz. The phone's Wi-Fi Direct group gets a wide channel instead, and
        // width is the whole difference: 25.8 MB/s peak over the 20 MHz hotspot against 58.8 over
        // a 160 MHz link between the same two devices.
        //
        // The matching change is isHosting() in Android's MainViewModel.kt, which adds Peer.Linux.
        // Both sides must move together or they will both sit waiting to join.
        Peer::Android => false,
        Peer::IOS | Peer::MacOS => true,
        Peer::Windows => false,
        Peer::Linux => match mode {
            Mode::Send(_) => false,
            Mode::Receive(_) => true,
        },
    }
}

pub async fn connect_to_peer<T: UI>(
    peer: Peer,
    mode: Mode,
    ssid: String,
    password: String,
    interface: WiFiInterface,
    ui: &T,
) -> Result<PeerResource, FCError> {
    if is_hosting(&peer, &mode) {
        // start hotspot
        ui.output(&format!("Starting hotspot {}", ssid));
        start_hotspot(&ssid, &password, &interface.0).await?;
        Ok(PeerResource::LinuxHotspot)
    } else {
        // join hotspot and find gateway
        ui.output(&format!(
            "Joining hotspot {} — this drops your WiFi connection until the transfer is done",
            ssid
        ));
        join_hotspot(&ssid, &password, &interface.0, ui).await?;
        ui.output("Joined. Waiting for the network to hand out an address...");
        loop {
            // println!("looking for gateway");
            task::yield_now().await;
            match find_gateway(&interface.0) {
                Ok(gateway) => {
                    if gateway != "" {
                        ui.output(&format!("Got an address, peer is at {}", gateway));
                        return Ok(PeerResource::WifiClient(gateway));
                    }
                }
                Err(e) => Err(e)?,
            }
            tokio::time::sleep(tokio::time::Duration::from_millis(200)).await;
        }
    }
}

// async, and using the async command runner, so that a cancel lands between (or during) the
// nmcli calls instead of after the whole sequence: `con up` is the slow one
async fn start_hotspot(ssid: &str, password: &str, interface: &str) -> Result<String, FCError> {
    let nmcli = "nmcli";
    let user_str = &format!("user:{}", get_username());
    let commands = vec![
        vec![
            "con",
            "add",
            "type",
            "wifi",
            "ifname",
            &interface,
            "con-name",
            ssid,
            // NEVER "yes": an autoconnecting profile is resurrected by NetworkManager whenever the
            // radio is free, so a profile that outlives the app (crash, window closed mid-transfer,
            // process killed) keeps the card in AP mode for good -- no normal networks, until it is
            // deleted by hand. The transfer activates it explicitly, so autoconnect buys nothing.
            "autoconnect",
            "no",
            "ssid",
            ssid,
            "connection.permissions",
            &user_str,
        ],
        vec![
            "con",
            "modify",
            ssid,
            "802-11-wireless.mode",
            "ap",
            "ipv4.method",
            "shared",
        ],
        vec!["con", "modify", ssid, "wifi-sec.key-mgmt", "wpa-psk"],
        // disable Protected Management Frames, which disables WPA3/SAE, which is necessary for M1 Macs to join Linux
        vec!["con", "modify", ssid, "wifi-sec.pmf", "disable"],
        // use AES, not TKIP
        vec!["con", "modify", ssid, "wifi-sec.pairwise", "ccmp"],
        vec!["con", "modify", ssid, "wifi-sec.group", "ccmp"],
        // use WPA2, not WPA
        vec!["con", "modify", ssid, "wifi-sec.proto", "rsn"],
        vec!["con", "modify", ssid, "wifi-sec.psk", password],
    ];
    for command in commands {
        let res = run_command_async(nmcli, Some(command)).await?;
        if !res.status.success() {
            let stderr = String::from_utf8_lossy(&res.stderr);
            fc_error(&format!("Could not start hotspot: {}", stderr))?;
        }
        // println!("output: {}", String::from_utf8_lossy(&res.stdout));
    }

    // Ask for 5 GHz, and only then settle for what we used to get.
    //
    // A profile that names no band is a 2.4 GHz profile: NetworkManager's AP default picks a bg
    // channel every time, and every hotspot this app has ever raised on Linux came up there
    // (journal, 白い熊's machine 2026-08-11: `Config: added 'frequency' value '2437'` -- channel 6).
    // That is the desktop twin of the LocalOnlyHotspot ceiling the Android side escaped with its
    // Wi-Fi Direct group, and it costs twice over: 2.4 GHz is the crowded band, and it is the band
    // the BLE credential exchange is already sitting in, so the radio contends with our own
    // Bluetooth.
    //
    // Channel 149 is the pick because it is the one 5 GHz range that is free of both encumbrances
    // on this card (`iw reg get`, self-managed phy, country DE): 5170-5250 carries IR-CONCURRENT
    // and NO-IR, so an AP may not beacon there once we have dropped the network we were joined to,
    // and everything from 5250 to 5710 is DFS. 5735-5755 has neither flag.
    //
    // One attempt, then the fallback. A channel the regulatory domain refuses does not fail fast --
    // NetworkManager sits on it for the full supplicant timeout, measured at 25.6s -- so a longer
    // ladder of candidates would spend minutes in the dark before landing where it started.
    const FIVE_GHZ_CHANNEL: &str = "149";
    let radios: [(&str, &str, &str); 2] = [
        ("a", FIVE_GHZ_CHANNEL, "5 GHz"),
        // Clearing both puts the profile back exactly as it was before this block existed.
        ("", "0", "2.4 GHz"),
    ];
    let mut last_error = String::new();
    for (band, channel, label) in radios {
        let res = run_command_async(
            nmcli,
            Some(vec![
                "con",
                "modify",
                ssid,
                "802-11-wireless.band",
                band,
                "802-11-wireless.channel",
                channel,
            ]),
        )
        .await?;
        if !res.status.success() {
            last_error = String::from_utf8_lossy(&res.stderr).to_string();
            continue;
        }
        let res = run_command_async(nmcli, Some(vec!["con", "up", ssid])).await?;
        if res.status.success() {
            return Ok(label.to_string());
        }
        last_error = String::from_utf8_lossy(&res.stderr).to_string();
    }
    fc_error(&format!("Could not start hotspot: {}", last_error))?;
    unreachable!("fc_error always returns Err")
}

// Deletes leftover NetworkManager connections from previous runs that crashed or were killed
// before stop_hotspot() could run (#51). Both hosting and joining create a connection named after
// the SSID: "flyingCarpet_" plus 4 hex characters when a desktop hosts, and "DIRECT-fc-" plus the
// password when the Android peer does. Returns the names of the connections deleted.
pub fn cleanup_stale_connections() -> Result<Vec<String>, FCError> {
    let output = run_command(
        "nmcli",
        Some(vec!["-t", "-f", "NAME,TYPE", "connection", "show"]),
    )?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        fc_error(&format!(
            "Could not list NetworkManager connections: {}",
            stderr
        ))?;
    }
    let mut deleted = vec![];
    for line in String::from_utf8_lossy(&output.stdout).lines() {
        // terse format is NAME:TYPE; the name can't contain a colon (nmcli escapes
        // them, and ours never do), so split on the last one
        let Some((name, connection_type)) = line.rsplit_once(':') else {
            continue;
        };
        // "DIRECT-fc-" as well as "flyingCarpet_": since Android hosts for us (is_hosting), a
        // joined profile is named after the peer's Wi-Fi Direct group, and one of those left by a
        // killed run is exactly as stranding as our own. The "fc-" keeps it to groups this app
        // created -- a plain "DIRECT-" prefix would match every Wi-Fi Direct network on the
        // machine, including ones we know nothing about.
        if connection_type == "802-11-wireless"
            && (name.starts_with("flyingCarpet_") || name.starts_with("DIRECT-fc-"))
        {
            let delete = run_command("nmcli", Some(vec!["connection", "delete", name]))?;
            if delete.status.success() {
                deleted.push(name.to_string());
            }
        }
    }
    Ok(deleted)
}

pub fn stop_hotspot(
    _peer_resource: Option<&PeerResource>,
    ssid: Option<&str>,
) -> Result<String, FCError> {
    if ssid.is_some() {
        let list = run_command("nmcli", Some(vec!["connection", "show"]))?;
        if String::from_utf8_lossy(&list.stdout).contains(ssid.unwrap()) {
            let options = Some(vec!["connection", "delete", ssid.unwrap()]);
            let command_output = run_command("nmcli", options)?;
            if !command_output.status.success() {
                let stderr = String::from_utf8_lossy(&command_output.stderr);
                fc_error(&format!("Error stopping hotspot: {}", stderr))?;
            }
            let output = String::from_utf8_lossy(&command_output.stdout);
            Ok(format!("Stop hotspot output: {}", output))
        } else {
            Ok(format!("SSID {} was not a known network", ssid.unwrap()))
        }
    } else {
        Ok(String::new())
    }
}

async fn join_hotspot<T: UI>(
    ssid: &str,
    password: &str,
    interface: &str,
    ui: &T,
) -> Result<(), FCError> {
    let nmcli = "nmcli";
    let user_str = &format!("user:{}", get_username());
    let commands = vec![
        vec![
            "con",
            "add",
            "type",
            "wifi",
            "ifname",
            &interface,
            "con-name",
            ssid,
            // NEVER "yes": an autoconnecting profile is resurrected by NetworkManager whenever the
            // radio is free, so a profile that outlives the app (crash, window closed mid-transfer,
            // process killed) keeps the card in AP mode for good -- no normal networks, until it is
            // deleted by hand. The transfer activates it explicitly, so autoconnect buys nothing.
            "autoconnect",
            "no",
            "ssid",
            ssid,
            "connection.permissions",
            &user_str,
        ],
        vec!["con", "modify", ssid, "wifi-sec.key-mgmt", "wpa-psk"],
        vec!["con", "modify", ssid, "wifi-sec.psk", password],
    ];
    for command in commands {
        let res = run_command_async(nmcli, Some(command)).await?;
        if !res.status.success() {
            let stderr = String::from_utf8_lossy(&res.stderr);
            fc_error(&format!("Error joining hotspot: {}", stderr))?;
        }
        // println!(
        //     "join hotspot output: {}",
        //     String::from_utf8_lossy(&res.stdout)
        // );
    }
    loop {
        let res = run_command_async(nmcli, Some(vec!["con", "up", ssid])).await?;
        if !res.status.success() {
            let stderr = String::from_utf8_lossy(&res.stderr);
            // Err(format!("Error joining hotspot: {}", stderr))?;
            let err_msg = format!("Error joining hotspot: {}. Retrying.", stderr);
            ui.output(&err_msg);
            println!("{}", err_msg);
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
        } else {
            break;
        }
    }
    Ok(())
}

pub fn get_wifi_interfaces() -> Result<Vec<InterfaceInfo>, FCError> {
    let command = "nmcli";
    let options = vec!["-t", "device"];
    let command_output = run_command(command, Some(options))?;
    let output = String::from_utf8_lossy(&command_output.stdout);
    let mut interfaces: Vec<InterfaceInfo> = vec![];
    for line in output.lines() {
        // Format: DEVICE:TYPE:STATE:CONNECTION
        let split_line: Vec<&str> = line.split(':').collect();
        if split_line.len() < 2 || split_line[1] != "wifi" {
            continue;
        }
        // ip is best-effort: hosting a hotspot doesn't require a connection
        let name = split_line[0].to_string();
        let ip = interface_ipv4(&name);
        interfaces.push(InterfaceInfo {
            name,
            guid: String::new(),
            ip,
        });
    }
    Ok(interfaces)
}

/// Returns the interface's usable IPv4 address as text, or None if it has none.
/// Link-local (169.254.x) addresses mean there's no real network.
fn interface_ipv4(interface_name: &str) -> Option<String> {
    let iface = WiFiInterface(interface_name.to_string(), String::new());
    let cidr = get_ip_cidr(&iface).ok()?;
    let ip = cidr.split('/').next()?.trim().to_string();
    if ip.is_empty() || ip.starts_with("169.254.") {
        None
    } else {
        Some(ip)
    }
}

/// Name of the interface owning the default route, if any (for preselection).
fn default_route_interface() -> Option<String> {
    let output = run_command("sh", Some(vec!["-c", "ip -4 route show default"])).ok()?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    // Format: "default via 192.168.1.1 dev eth0 proto dhcp ..."
    let tokens: Vec<&str> = stdout.split_whitespace().collect();
    let dev_idx = tokens.iter().position(|&t| t == "dev")?;
    tokens.get(dev_idx + 1).map(|s| s.to_string())
}

// Where the peer is, once we have joined its network. Three sources, weakest assumption last.
//
// This used to be `route -n | grep <iface> | grep UG`, which has two faults. `route` is net-tools,
// deprecated and no longer installed by default on modern distributions -- it is absent on 白い熊's
// Tuxedo OS -- so the pipeline printed "route: not found" to stderr, produced nothing on stdout,
// and this returned an empty string. connect_to_peer treats empty as "not ready yet" and polls, so
// the joining device sat on "Waiting for the network to hand out an address..." for ever while
// NetworkManager had in fact handed it one seconds earlier (白い熊, 2026-08-11: address
// 192.168.49.178 at 22:28:56, app still waiting at 22:30:27 when it was cancelled).
//
// It stayed hidden because this side never joined: the desktop hosted for every peer that mattered
// until is_hosting() changed. Anyone joining a Windows host on a net-tools-less machine had the
// same bug waiting for them.
//
// The second fault is the UG flag itself. A Wi-Fi Direct group owner is not a router -- it offers
// an address and, having no internet to hand out, need not offer a default route at all. So even
// with net-tools present there may be no UG line to find, and the DHCP server's own address is
// what we actually want: for a group owner that is the peer.
fn find_gateway(interface: &str) -> Result<String, FCError> {
    // 1. The default route, via iproute2 (present everywhere net-tools is not).
    let output = run_command(
        "ip",
        Some(vec!["-4", "route", "show", "default", "dev", interface]),
    )?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    for line in stdout.lines() {
        let mut parts = line.split_whitespace();
        while let Some(token) = parts.next() {
            if token == "via" {
                if let Some(address) = parts.next() {
                    return Ok(address.to_string());
                }
            }
        }
    }

    // 2. NetworkManager's view of the lease, which knows a gateway even when no default route was
    //    installed (another connection may already own the default).
    if let Ok(output) = run_command(
        "nmcli",
        Some(vec!["-g", "IP4.GATEWAY", "device", "show", interface]),
    ) {
        let gateway = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if !gateway.is_empty() && gateway != "--" {
            return Ok(gateway);
        }
    }

    // 3. The DHCP server that answered us. On a Wi-Fi Direct group that is the group owner, i.e.
    //    the peer -- the one case where there is no gateway to find because there is no gateway.
    if let Ok(output) = run_command(
        "nmcli",
        Some(vec!["-g", "DHCP4.OPTION", "device", "show", interface]),
    ) {
        let text = String::from_utf8_lossy(&output.stdout);
        for option in text.split(['|', '\n']) {
            if let Some((key, value)) = option.split_once('=') {
                if key.trim() == "dhcp_server_identifier" {
                    let value = value.trim();
                    if !value.is_empty() {
                        return Ok(value.to_string());
                    }
                }
            }
        }
    }

    // Nothing yet: the caller polls, so this means "ask again in a moment".
    Ok(String::new())
}

/// Get local IPv4 address on the specified interface (works for WiFi or wired)
pub fn get_local_ip(interface: &WiFiInterface) -> Result<std::net::Ipv4Addr, FCError> {
    let cidr = get_ip_cidr(interface)?;
    let ip_str = cidr.split('/').next().unwrap_or("");
    ip_str.parse().map_err(|e| FCError {
        message: format!("Failed to parse IP address '{}': {}", ip_str, e),
    })
}

/// Get the subnet prefix length (e.g. 24 for /24) on the specified interface
pub fn get_prefix_length(interface: &WiFiInterface) -> Result<u8, FCError> {
    let cidr = get_ip_cidr(interface)?;
    let prefix_str = cidr.split('/').nth(1).unwrap_or("24");
    prefix_str.parse().map_err(|e| FCError {
        message: format!("Failed to parse prefix length '{}': {}", prefix_str, e),
    })
}

/// Returns the CIDR notation (e.g. "192.168.1.100/24") for the interface
fn get_ip_cidr(interface: &WiFiInterface) -> Result<String, FCError> {
    let ip_command = format!(
        "ip -4 addr show {} | grep inet | awk '{{print $2}}'",
        interface.0
    );
    let output = run_command("sh", Some(vec!["-c", &ip_command]))?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    let cidr = stdout.trim();

    if cidr.is_empty() {
        fc_error(&format!(
            "No IPv4 address found on interface {}",
            interface.0
        ))?;
    }

    Ok(cidr.to_string())
}

/// No-op on Linux: firewall rules are only managed on Windows.
pub async fn ensure_firewall_rules<T: UI>(_ui: &T) -> Result<(), FCError> {
    Ok(())
}

/// Check if interface has an active network connection
pub fn has_network_connection(interface: &WiFiInterface) -> Result<bool, FCError> {
    // Check if interface has an IP address assigned
    let ip_command = format!("ip -4 addr show {} | grep inet", interface.0);
    let output = run_command("sh", Some(vec!["-c", &ip_command]))?;
    let stdout = String::from_utf8_lossy(&output.stdout);

    Ok(!stdout.trim().is_empty())
}

/// Get WiFi and Ethernet interfaces that have an IPv4 address, for shared network
/// mode (which works over wired connections too, unlike hotspot mode). Filtering by
/// nmcli device type keeps virtual interfaces (docker0, VPN tunnels, bridges) out of
/// the interface chooser.
pub fn get_connected_interfaces() -> Result<Vec<InterfaceInfo>, FCError> {
    let command_output = run_command("nmcli", Some(vec!["-t", "device"]))?;
    let output = String::from_utf8_lossy(&command_output.stdout);
    let default_iface = default_route_interface();
    let mut with_gateway = Vec::new();
    let mut without_gateway = Vec::new();
    for line in output.lines() {
        // Format: DEVICE:TYPE:STATE:CONNECTION
        let split_line: Vec<&str> = line.split(':').collect();
        if split_line.len() < 2 {
            continue;
        }
        if split_line[1] != "wifi" && split_line[1] != "ethernet" {
            continue;
        }
        let name = split_line[0].to_string();
        // omit interfaces without a usable IPv4: they can't work in shared mode
        let ip = match interface_ipv4(&name) {
            Some(ip) => ip,
            None => continue,
        };
        let is_default = default_iface.as_deref() == Some(name.as_str());
        let info = InterfaceInfo {
            name,
            guid: String::new(),
            ip: Some(ip),
        };
        // list the default-route interface first so the UI can preselect it
        if is_default {
            with_gateway.push(info);
        } else {
            without_gateway.push(info);
        }
    }
    with_gateway.append(&mut without_gateway);
    Ok(with_gateway)
}

fn get_username() -> String {
    std::env::var("USER")
        .or_else(|_| std::env::var("USERNAME"))
        .unwrap_or_else(|_| "user".to_string())
}

// These drive the real wifi card, so they're #[ignore]d: a plain `cargo test` would take the
// machine's wifi down for the length of the run (start_and_stop_hotspot puts the interface
// into AP mode for five seconds, join_hotspot tries to associate). Run them deliberately,
// one at a time, with `cargo test -- --ignored --test-threads=1`.
#[cfg(test)]
mod test {
    use crate::{PeerResource, UI};

    use super::get_wifi_interfaces;

    #[tokio::test]
    #[ignore = "starts a real hotspot on the wifi card"]
    async fn start_and_stop_hotspot() {
        let ssid = "flyingCarpet_1234";
        let password = "password";
        let _pr = PeerResource::WifiClient("".to_string());
        let interface = &get_wifi_interfaces().expect("no wifi interface present")[0].name;
        crate::network::start_hotspot(ssid, password, interface)
            .await
            .unwrap();
        tokio::time::sleep(std::time::Duration::from_secs(5)).await;
        crate::network::stop_hotspot(Some(&_pr), Some(ssid)).unwrap();
    }

    #[test]
    #[ignore = "joins a real hotspot; also needs a runtime, see the TODO in lib.rs"]
    fn join_hotspot() {
        #[derive(Clone)]
        struct TestUI {}
        impl UI for TestUI {
            fn ask_file_conflict(&self, _n: &str, _l: u64, _i: u64, _same: bool, _more: bool) {}
            fn output(&self, _msg: &str) {}
            fn show_progress_bar(&self) {}
            fn update_progress_bar(&self, _percent: u8) {}
            fn update_total_progress_bar(&self, _percent: u8) {}
            fn update_progress_details(&self, _current: &str, _total: &str) {}
            fn enable_ui(&self) {}
            fn show_pin(&self, _pin: &str) {}
        }

        let ssid = "";
        let password = "";
        let pr = PeerResource::WifiClient("".to_string());
        let interface = &get_wifi_interfaces().expect("no wifi interface present")[0].name;
        let interface = interface.to_string();
        let (tx, mut rx) = tokio::sync::mpsc::channel::<()>(1);
        tokio::spawn(async move {
            crate::network::join_hotspot(ssid, password, &interface, &TestUI {})
                .await
                .unwrap();
            std::thread::sleep(std::time::Duration::from_secs(20));
            crate::network::stop_hotspot(Some(&pr), Some(ssid)).unwrap();
            tx.send(()).await.unwrap();
        });
        rx.blocking_recv().unwrap();
    }

    #[test]
    fn find_gateway() {
        let interface = &get_wifi_interfaces().expect("no wifi interface present")[0].name;
        let gateway = crate::network::find_gateway(interface).unwrap();
        println!("interface: {}", interface);
        println!("gateway: {}", gateway);
    }
}
