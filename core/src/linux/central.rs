use bluer::{
    gatt::{
        remote::{Characteristic, CharacteristicWriteRequest},
        WriteOp,
    },
    Adapter, AdapterEvent, Address, Device, DiscoveryFilter, DiscoveryTransport, ErrorKind, Result,
    Uuid,
};
use futures::{pin_mut, StreamExt};
use std::{
    collections::{HashMap, HashSet},
    time::{Duration, Instant},
};
use tokio::time::{sleep, timeout};

use super::SERVICE_UUID;
use crate::{
    bluetooth::{
        OS, OS_CHARACTERISTIC_UUID, PASSWORD_CHARACTERISTIC_UUID, SSID_CHARACTERISTIC_UUID,
    },
    network::is_hosting,
    utils::{generate_password, get_key_and_ssid},
    Mode, Peer,
};

// the keys are the UUID string constants, so they outlive the device -- spelling that out lets the
// caller keep the characteristics after deciding which device to keep
pub async fn find_characteristics(device: &Device) -> Result<HashMap<&'static str, Characteristic>> {
    let addr = device.address();
    let uuids = device.uuids().await?.unwrap_or_default();

    let os_characteristic_uuid = Uuid::parse_str(OS_CHARACTERISTIC_UUID).unwrap();
    let ssid_characteristic_uuid = Uuid::parse_str(SSID_CHARACTERISTIC_UUID).unwrap();
    let password_characteristic_uuid = Uuid::parse_str(PASSWORD_CHARACTERISTIC_UUID).unwrap();
    println!("Discovered device {} with service UUIDs {:?}", addr, &uuids);
    let md = device.manufacturer_data().await?;
    println!("    Manufacturer data: {:x?}", &md);

    if uuids.contains(&Uuid::parse_str(SERVICE_UUID).unwrap()) {
        println!("    Device provides our service!");
        let mut characteristics = HashMap::new();

        sleep(Duration::from_secs(2)).await;
        // Connect unconditionally. is_connected() is not a promise that GATT is usable: a dual-mode
        // phone connected over BR/EDR for audio or PAN reports connected, we skipped connecting on
        // that basis, read the service list out of BlueZ's cache, and then every ATT operation
        // failed with "Not connected" because no LE link existed. Connect() on an already-connected
        // device is harmless, so ask for it either way.
        if device.is_connected().await.unwrap_or(false) {
            println!("    Already connected, ensuring the link is usable");
        } else {
            println!("    Connecting...");
        }
        let mut retries = 2;
        loop {
            match device.connect().await {
                Ok(()) => break,
                Err(err) if retries > 0 => {
                    println!("    Connect error: {}", &err);
                    retries -= 1;
                }
                Err(err) => return Err(err),
            }
        }
        println!("    Connected");

        // Enumerating before BlueZ has resolved services yields whatever happens to be cached,
        // which is how a stale service list reached the code above in the first place.
        for _ in 0..20 {
            if device.is_services_resolved().await.unwrap_or(false) {
                break;
            }
            sleep(Duration::from_millis(500)).await;
        }

        // bond?
        // sleep(Duration::from_secs(2)).await;
        // if !device.is_paired().await? {
        //     println!("    Pairing...");
        //     let mut retries = 2;
        //     loop {
        //         match device.pair().await {
        //             Ok(()) => break,
        //             Err(err) if retries > 0 => {
        //                 println!("    Pair error: {}", &err);
        //                 retries -= 1;
        //             }
        //             Err(err) => return Err(err),
        //         }
        //     }
        //     println!("    Paired");
        // } else {
        //     println!("    Already paired");
        // }

        // sleep(Duration::from_secs(2)).await;
        // println!("    Enumerating services...");
        // if !device.is_services_resolved().await? {
        //     println!("Not resolved...");
        //     sleep(Duration::from_secs(2)).await;
        // } else {
        //     println!("Services are resolved: {:?}", device.services().await?);
        //     let data = device.service_data().await?;
        //     println!("Data: {:?}", data);
        // }
        // let mut events = device.events().await.unwrap();
        // while let Some(ev) = events.next().await {
        //     println!("Received event {:?}", ev);
        // }
        for service in device.services().await? {
            let uuid = service.uuid().await?;
            println!("    Service UUID: {}", &uuid);
            // Diagnostics only -- never fail the transfer over them. BlueZ populates a
            // characteristic's MTU property only once the ATT exchange has happened, so reading
            // properties this early returns "No such property 'MTU'", and with a `?` that killed
            // the whole handshake after the peer had been found and its service resolved.
            match service.all_properties().await {
                Ok(p) => println!("    Service data: {:?}", p),
                Err(e) => println!("    Service data unavailable: {}", e),
            }
            if uuid == Uuid::parse_str(SERVICE_UUID).unwrap() {
                println!("    Found our service!");
                for char in service.characteristics().await? {
                    let uuid = char.uuid().await?;
                    println!("    Characteristic UUID: {}", &uuid);
                    match char.all_properties().await {
                        Ok(p) => println!("    Characteristic data: {:?}", p),
                        Err(e) => println!("    Characteristic data unavailable: {}", e),
                    }
                    if uuid == os_characteristic_uuid {
                        println!("    (reading this one will ask both devices to pair)");
                        characteristics.insert(OS_CHARACTERISTIC_UUID, char);
                        println!("found OS characteristic")
                    } else if uuid == ssid_characteristic_uuid {
                        characteristics.insert(SSID_CHARACTERISTIC_UUID, char);
                        println!("found ssid characteristic")
                    } else if uuid == password_characteristic_uuid {
                        characteristics.insert(PASSWORD_CHARACTERISTIC_UUID, char);
                        println!("found password characteristic")
                    }
                }
            }
        }

        if characteristics.contains_key(OS_CHARACTERISTIC_UUID)
            && characteristics.contains_key(SSID_CHARACTERISTIC_UUID)
            && characteristics.contains_key(PASSWORD_CHARACTERISTIC_UUID)
        {
            Ok(characteristics)
        } else {
            let e = bluer::Error {
                kind: bluer::ErrorKind::ServicesUnresolved,
                message: "Did not read all Flying Carpet characteristics from peer.".to_string(),
            };
            Err(e)
        }
    } else {
        let err = bluer::Error {
            kind: ErrorKind::ServicesUnresolved,
            message: "Could not find service UUID on scanned device".to_string(),
        };
        Err(err)
    }
}

// `rejected` holds the addresses we have already tried and found not to be the peer. Without it a
// beacon that advertises an empty service list is handed back on every call, gets rejected, and is
// handed back again the moment discovery restarts -- a hot loop that spun 1074 times in one session.
// Pairing makes a device permanent in BlueZ. DeviceAdded only ever fires for an object that was
// pruned and later seen again, and paired devices are never pruned -- so once the peer is bonded,
// discovery stops announcing it entirely and the scan below can never see it. It has to be looked
// up among the devices BlueZ already knows. "Paired, offers our service, and has a current RSSI"
// is the test: the RSSI is what separates the peer that is on the air right now from stale records,
// and requiring paired keeps us off other phones that merely ran this app once and still have our
// UUID -- connecting to one of those yields a cached GATT database whose reads all fail.
async fn bonded_peer_on_air(
    adapter: &Adapter,
    service_uuid: &Uuid,
    rejected: &HashSet<Address>,
) -> Option<Device> {
    let mut fallback: Option<Device> = None;
    for addr in adapter.device_addresses().await.ok()? {
        if rejected.contains(&addr) {
            continue;
        }
        let device = match adapter.device(addr) {
            Ok(d) => d,
            Err(_) => continue,
        };
        let known = device.uuids().await.unwrap_or_default().unwrap_or_default();
        if !known.contains(service_uuid) || !device.is_paired().await.unwrap_or(false) {
            continue;
        }
        let rssi = match device.rssi().await {
            Ok(Some(rssi)) => rssi,
            _ => continue,
        };
        // LE privacy makes the peer appear twice: its identity record, whose address is public for
        // a dual-mode phone, and a throwaway object for the random address it happens to be
        // advertising from right now. Both resolve to the same bond and both look paired, but only
        // the identity record carries a usable GATT database -- the temporary one hands back a
        // cached copy whose reads all fail with "Not connected". Prefer public; keep a random one
        // as a fallback, since a peer's identity address can legitimately be random.
        match device.address_type().await {
            Ok(bluer::AddressType::LePublic) => {
                println!("Bonded peer {} is on the air (RSSI {})", addr, rssi);
                return Some(device);
            }
            _ => {
                if fallback.is_none() {
                    println!("Holding {} (random address, RSSI {})", addr, rssi);
                    fallback = Some(device);
                }
            }
        }
    }
    if let Some(device) = &fallback {
        println!("No public identity on the air; using {}", device.address());
    }
    fallback
}

pub async fn scan(adapter: &Adapter, rejected: &HashSet<Address>) -> bluer::Result<Device> {
    let service_uuid = Uuid::parse_str(SERVICE_UUID).expect("Could not parse service UUID");
    let mut uuids = HashSet::new();
    uuids.insert(service_uuid);

    let filter = DiscoveryFilter {
        // LE, not Auto. With Auto, BlueZ merges the peer's BR/EDR record into the same device
        // object -- a phone arrives carrying A2DP, HFP, PBAP, MAP and the rest -- and Connect()
        // then brings up those classic profiles instead of an ATT link. Services still "resolve"
        // from BlueZ's cache, the characteristics are all found, and then the first read fails with
        // "Not connected" because no LE connection was ever made. That is also why the earliest
        // transfers worked and later ones did not: the classic profiles only get merged in once the
        // phone has been bonded and SDP has run.
        transport: DiscoveryTransport::Le,
        uuids,
        ..Default::default()
    };
    adapter.set_discovery_filter(filter).await?;
    println!(
        "Using discovery filter:\n{:#?}\n\n",
        adapter.discovery_filter().await
    );

    {
        println!(
            "Discovering on Bluetooth adapter {} with address {}\n",
            adapter.name(),
            adapter.address().await?
        );
        let discover = adapter.discover_devices().await?;
        pin_mut!(discover);
        // Give BlueZ a moment to attach RSSIs to what it is hearing, then check the bonded peer
        // first -- before consuming any discovery events, since those announce every other phone
        // in the room that has ever run this app and would otherwise win the race.
        sleep(Duration::from_secs(2)).await;
        if let Some(device) = bonded_peer_on_air(adapter, &service_uuid, rejected).await {
            return Ok(device);
        }
        // Devices that offer our service but that BlueZ is only replaying from its cache. Once we
        // keep the bond, the peer exists twice: the stale object for the random address it used
        // last time, and the resolved identity address it is advertising from now. The stale one is
        // announced first, and picking it produced "Already connected" followed by unresolvable GATT
        // services. RSSI is what tells them apart -- BlueZ only has one for a device it is hearing
        // right now -- so a live advertiser always wins. Cached ones are kept as a fallback rather
        // than discarded, in case a peer ever shows up without an RSSI; skipping them outright would
        // dead-end, since DeviceAdded fires only once per device per discovery session.
        // A device BlueZ already knows is announced once, at the start of discovery, and never
        // again -- when its advertisement then arrives BlueZ only updates properties, with no
        // second DeviceAdded. So a held candidate has to be re-examined as time passes rather than
        // waited on: each tick we look again to see whether it has picked up an RSSI, which means
        // it is the one on the air. After a few quiet ticks we take one anyway.
        let mut cached: Vec<Device> = Vec::new();
        let mut quiet_ticks = 0;
        // The bonded peer is never announced by discovery, so it is only ever found by the sweep --
        // which means the sweep has to run on a clock of its own. Hanging it off the "no events for
        // a while" branch starved it completely: in a room with this many BLE devices something is
        // always being announced, the timeout almost never fires, and the peer is never looked for.
        let mut last_sweep = Instant::now();
        loop {
            if last_sweep.elapsed() >= Duration::from_secs(3) {
                last_sweep = Instant::now();
                if let Some(device) = bonded_peer_on_air(adapter, &service_uuid, rejected).await {
                    return Ok(device);
                }
                for device in &cached {
                    if let Ok(Some(rssi)) = device.rssi().await {
                        println!("{} is on the air now (RSSI {})", device.address(), rssi);
                        return Ok(device.clone());
                    }
                }
            }
            let evt = match timeout(Duration::from_secs(1), discover.next()).await {
                Ok(Some(evt)) => evt,
                Ok(None) => break,
                Err(_) => {
                    quiet_ticks += 1;
                    if quiet_ticks >= 15 {
                        if let Some(device) = cached.pop() {
                            println!("No live advertiser; trying cached {}", device.address());
                            return Ok(device);
                        }
                    }
                    continue;
                }
            };
            quiet_ticks = 0;
            match evt {
                AdapterEvent::DeviceAdded(addr) => {
                    // let device = adapter.connect_device(addr, bluer::AddressType::LePublic).await?;
                    let device = adapter.device(addr)?;
                    if rejected.contains(&addr) {
                        continue;
                    }
                    // The discovery filter only governs advertising reports: BlueZ still announces
                    // devices it already knows -- bonded phones, headsets -- as soon as discovery
                    // starts. Returning the first one handed a paired audio device to
                    // find_characteristics(), which killed the whole transfer with "Could not find
                    // service UUID on scanned device". Our peer always puts the service UUID in its
                    // advertisement -- that is the only thing in it -- so require it. An earlier
                    // version also tried devices with no service list at all, which just meant a
                    // fitness band with an empty advertisement got picked on every single pass.
                    let advertised = device.uuids().await.unwrap_or_default().unwrap_or_default();
                    if !advertised.contains(&service_uuid) {
                        println!("Ignoring {}: does not offer our service", addr);
                        continue;
                    }
                    match device.rssi().await.unwrap_or_default() {
                        Some(rssi) => {
                            println!("{} is advertising our service now (RSSI {})", addr, rssi);
                            return Ok(device);
                        }
                        None => {
                            println!("Holding {}: offers our service but is not on the air", addr);
                            cached.push(device);
                        }
                    }
                }
                AdapterEvent::DeviceRemoved(addr) => {
                    println!("Device removed {addr}");
                }
                other_event => println!("Processed other event: {:?}", other_event),
            }
        }
        println!("Stopping discovery");
    }
    Err(bluer::Error {
        kind: ErrorKind::NotFound,
        message: "Exited scan() without finding device".to_string(),
    })
}

pub async fn exchange_info(
    characteristics: HashMap<&str, Characteristic>,
    mode: &Mode,
    interface: &str,
) -> bluer::Result<(String, String, String)> {
    // have to use this with write_ext() for the write requests: iOS wouldn't receive unconfirmed writes, which WriteOp::Request provides.
    // not sure if iOS requires it or if i did somehow. bluer seems to default to WriteOp::Command which has no confirmation.
    let write_req = CharacteristicWriteRequest {
        offset: 0,
        op_type: WriteOp::Request,
        prepare_authorize: true,
        ..Default::default()
    };

    // read peer's OS
    let os_char = &characteristics[OS_CHARACTERISTIC_UUID];
    let value = os_char.read().await?;
    let peer_os = String::from_utf8(value).expect("Peer OS value was not utf-8");
    println!("Peer OS: {}", peer_os);
    sleep(Duration::from_secs(1)).await;
    // write our OS
    os_char.write_ext(OS.as_bytes(), &write_req).await?;
    println!("Wrote OS to peer");
    sleep(Duration::from_secs(1)).await;

    let ssid_char = &characteristics[SSID_CHARACTERISTIC_UUID];
    let password_char = &characteristics[PASSWORD_CHARACTERISTIC_UUID];
    if is_hosting(&Peer::from(peer_os.as_str()), mode) {
        let password = generate_password();
        let (_, ssid) = get_key_and_ssid(&password);
        // Bring the hotspot up BEFORE telling the peer about it. The other way round -- which is
        // what happens if this is left to connect_to_peer() after the handshake returns -- hands
        // the phone an SSID that is not on the air yet: it fires its network request immediately,
        // finds nothing, and the user gets "no device found" and has to hit retry. Creating the AP
        // means nmcli tearing down the current WiFi association and bringing the radio up in AP
        // mode, which is the several seconds the peer would otherwise spend searching for nothing.
        println!("Starting hotspot {} before handing over credentials", ssid);
        crate::network::start_hotspot(&ssid, &password, interface).map_err(|e| bluer::Error {
            kind: ErrorKind::Failed,
            message: format!("Could not start hotspot: {}", e),
        })?;
        // nmcli returns as soon as NetworkManager marks the connection active, which is a moment
        // before the radio is actually beaconing. The peer scans the instant it has the
        // credentials, so without this its first scan comes back empty and the user has to hit
        // retry on the system's network picker.
        sleep(Duration::from_secs(3)).await;
        println!("Hotspot {} is up and beaconing", ssid);
        // write ssid and password
        ssid_char.write_ext(ssid.as_bytes(), &write_req).await?;
        // let CharacteristicWriteRequest
        // ssid_char.write_ext(value, req);
        println!("Wrote SSID to peer");
        sleep(Duration::from_secs(1)).await;
        password_char
            .write_ext(password.as_bytes(), &write_req)
            .await?;
        println!("Wrote password to peer");
        sleep(Duration::from_secs(1)).await;
        Ok((peer_os, ssid, password))
    } else {
        // read ssid and password
        let ssid = ssid_char.read().await?;
        let ssid = String::from_utf8(ssid).expect("SSID was not UTF-8");
        println!("Peer's SSID: {}", ssid);
        let password = password_char.read().await?;
        let password = String::from_utf8(password).expect("Password was not UTF-8");
        println!("Peer's password: {}", password);
        Ok((peer_os, ssid, password))
    }
}
