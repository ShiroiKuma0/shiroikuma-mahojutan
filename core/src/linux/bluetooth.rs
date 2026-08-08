mod central;
mod peripheral;

use bluer::{
    agent::{Agent, AgentHandle, ReqError, RequestConfirmation},
    Adapter, Session,
};
use central::{exchange_info, find_characteristics};
use std::{
    mem::discriminant,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::{sync::mpsc, sync::Mutex as TokioMutex, time::sleep};

use crate::{
    error::{fc_error, FCError},
    utils::{generate_password, get_key_and_ssid, with_progress, BluetoothMessage},
    we_supply_credentials, ConnectionMode, Mode, Peer, UI,
};

impl From<bluer::Error> for FCError {
    fn from(value: bluer::Error) -> Self {
        FCError {
            message: format!("Bluer error: {}", value),
        }
    }
}

pub(crate) const OS: &str = "linux";
const SERVICE_UUID: &str = "A70BF3CA-F708-4314-8A0E-5E37C259BE5C";
pub(crate) const OS_CHARACTERISTIC_UUID: &str = "BEE14848-CC55-4FDE-8E9D-2E0F9EC45946";
pub(crate) const SSID_CHARACTERISTIC_UUID: &str = "0D820768-A329-4ED4-8F53-BDF364EDAC75";
pub(crate) const PASSWORD_CHARACTERISTIC_UUID: &str = "E1FA8F66-CF88-4572-9527-D5125A2E0762";
// A host that hasn't generated its credentials yet answers an SSID read with this (Windows)
// or with an empty string (Android); centrals treat both as "not ready, re-read".
pub(crate) const NO_SSID: &str = "NONE";

/// Registers a Bluetooth pairing agent for as long as the returned handle is held.
///
/// Why this exists: Flying Carpet's BLE characteristics require an encrypted (bonded)
/// link, so reading them triggers pairing. With no app-registered agent, that pairing can
/// only be completed by the desktop's *system* agent — i.e. the manual System-Settings
/// pairing that macOS<->Linux transfers currently require. Registering our own agent lets
/// pairing complete automatically during a transfer, in both directions.
///
/// `request_confirmation` gives us the DisplayYesNo capability (Numeric Comparison — the
/// 6-digit compare). That association model is what preserves MITM protection, which the
/// whole security model depends on (the Noise NNpsk0 PSK is the transfer password, shared
/// over this BLE channel; if pairing degrades to "Just Works" there is no MITM protection).
///
/// The passkey is surfaced through the same UI flow Windows uses: `ui.show_pin` emits the
/// `showPin` event, the frontend asks the user whether the code matches the peer's, and the
/// answer comes back over `ble_ui_rx`. Rejecting fails the pairing (ReqError::Rejected).
async fn register_pairing_agent<T: UI>(
    session: &Session,
    ui: &T,
    ble_ui_rx: mpsc::Receiver<bool>,
    bt_tx: mpsc::Sender<BluetoothMessage>,
) -> bluer::Result<AgentHandle> {
    // The agent closures must be Sync; UI is only Clone + Send, and the receiver needs
    // exclusive access — so both go behind mutexes.
    let ui = Arc::new(Mutex::new(ui.clone()));
    let ble_ui_rx = Arc::new(TokioMutex::new(ble_ui_rx));
    let agent = Agent {
        request_default: true,
        request_confirmation: Some(Box::new(move |req: RequestConfirmation| {
            let ui = ui.clone();
            let ble_ui_rx = ble_ui_rx.clone();
            let bt_tx = bt_tx.clone();
            Box::pin(async move {
                println!(
                    "BLE pairing passkey with {} (confirm it matches the other device): {:06}",
                    req.device, req.passkey
                );
                let mut rx = ble_ui_rx.lock().await;
                // discard any stale answer from an earlier request the user answered too late
                while rx.try_recv().is_ok() {}
                {
                    let ui = ui.lock().expect("Could not lock UI mutex");
                    ui.show_pin(&format!("{:06}", req.passkey));
                }
                let approved = rx.recv().await.unwrap_or(false);
                if approved {
                    Ok(())
                } else {
                    println!("User rejected Bluetooth pairing");
                    // Unblock a peripheral-mode transfer, which sits waiting on this channel
                    // for GATT activity that will now never come. (Central mode never reads
                    // the channel; it errors through the bonding socket instead.)
                    let _ = bt_tx.try_send(BluetoothMessage::UserCanceled);
                    Err(ReqError::Rejected)
                }
            })
        })),
        request_authorization: Some(Box::new(|_req| Box::pin(async move { Ok(()) }))),
        ..Default::default()
    };
    session.register_agent(agent).await
}

pub async fn check_support() -> Result<(), FCError> {
    let session = Session::new().await?;
    let adapter = session.default_adapter().await?;
    adapter.set_powered(true).await?;
    println!("Bluetooth is supported");
    Ok(())
}

pub async fn get_adapter() -> Result<Adapter, FCError> {
    let session = Session::new().await?;
    let adapter = session.default_adapter().await?;
    adapter.set_powered(true).await?;
    println!("Bluetooth is supported");
    Ok(adapter)
}

pub async fn negotiate_bluetooth<T: UI>(
    mode: &Mode,
    ble_ui_rx: mpsc::Receiver<bool>,
    ui: &T,
    connection_mode: ConnectionMode,
) -> Result<(String, String, String), FCError> {
    // TODO: dedup with check_support(), but can't return adapter from it because windows doesn't, unless we stub which is annoying to pass it back into this.
    let session = Session::new().await?;
    let adapter = session.default_adapter().await?;
    adapter.set_powered(true).await?;

    // Register our pairing agent for the whole transfer so pairing can complete without a
    // manual system-menu pairing. Held via _agent_handle until this function returns.
    // peripheral::advertise() is handed this session's adapter, so the agent lives on the
    // same D-Bus connection that serves the GATT application and is guaranteed to handle
    // any pairing triggered while advertising.
    // Bluetooth event channel: the GATT characteristic callbacks (peripheral mode) and the
    // pairing agent's rejection path both send into it.
    let (bt_tx, bt_rx) = mpsc::channel(1);
    let _agent_handle = register_pairing_agent(&session, ui, ble_ui_rx, bt_tx.clone()).await?;

    // Nothing of ours is scanning yet, so a discovery already in progress belongs to something
    // else on this machine -- a Bluetooth applet or settings page, typically. It matters: a
    // classic-Bluetooth inquiry runs 10.24 seconds at a time, back to back, and the radio cannot
    // establish an LE connection while it does. Measured on 白い熊's machine 2026-08-08: the peer
    // was found in 16ms and the connection to it then took 61 seconds and failed
    // (le-connection-abort-by-local), with btmon showing an uninterrupted inquiry loop through
    // the whole window. We cannot stop another program's scan, so say so instead of letting it
    // look like our own slowness.
    if adapter.is_discovering().await.unwrap_or(false) {
        ui.output(
            "Note: another program on this computer is scanning for Bluetooth devices right now \
             (a Bluetooth applet or settings window, usually). Its scan owns the radio, which can \
             stretch the connection below from a second to a minute or fail it outright. Closing \
             it makes Bluetooth transfers much faster.",
        );
    }

    // Bonds are never removed on cleanup. Linux used to drop its half of the bond after a
    // successful transfer with any non-macOS peer, on the premise that "Windows and Android
    // re-pair per transfer, so removing is safe". That premise is false for both: the
    // Windows central path short-circuits on AlreadyPaired and reuses the bond
    // (core/src/windows/central.rs), and the Android code has no removeBond call anywhere.
    // So the removal left every peer holding a bond Linux had forgotten, and the peer's next
    // connection tried to encrypt with an LTK we no longer had -- which surfaces as
    // CBError 14 on macOS and, on Windows, as a successful connect whose GATT database comes
    // back empty (see docs/windows-ble-gatt-0x8000ffff.md, 2026-07-25 observation). macOS was
    // never a special case; it was just the first platform where the symptom was legible.
    //
    // The deliberate bond removal for a *poisoned* bond is still in the central branch below:
    // that one fires on characteristic-discovery failure, where a stale bond is the suspected
    // cause rather than the casualty.

    if let Mode::Send(_) = mode {
        // acting as peripheral
        let tx = bt_tx;
        let mut rx = bt_rx;
        let password = generate_password();
        let (_, ssid) = get_key_and_ssid(&password);
        let (app_handle, adv_handle, peer_address) =
            peripheral::advertise(&adapter, tx, &ssid, &password).await?;
        ui.output("Started Bluetooth advertisement, waiting for receiving device...");
        ui.output("Nothing happens here until the other device starts its transfer and finds us.");
        // The exchange runs in a block so every exit -- success or error (a rejected
        // pairing, an unexpected message) -- shares the teardown below. The error paths
        // used to return without dropping the GATT service or the link, leaving the next
        // transfer to inherit a live ACL in the opposite role (law 9 in
        // docs/bluetooth-field-guide.md, the §3a bug).
        let exchange: Result<(String, String, String), FCError> = async {
            let peer_os = match with_progress(
                ui,
                "Advertising over Bluetooth, waiting for the other device to connect",
                process_bluetooth_message(BluetoothMessage::PeerOS("".to_string()), &mut rx, ui),
            )
            .await?
            {
                BluetoothMessage::PeerOS(os) => os,
                other => Err(FCError {
                    message: format!(
                        "Received unexpected BluetoothMessage when waiting for peer OS: {:?}",
                        other
                    ),
                })?,
            };

            println!("Removing advertisement");
            drop(adv_handle);

            let peer = Peer::try_from(peer_os.as_str())?;
            if we_supply_credentials(connection_mode, &peer, mode) {
                // wait for peer to read our ssid and password
                with_progress(
                    ui,
                    "Waiting for the other device to read our network name",
                    process_bluetooth_message(BluetoothMessage::PeerReadSsid, &mut rx, ui),
                )
                .await?;
                println!("Peer read SSID");
                with_progress(
                    ui,
                    "Waiting for the other device to read our password",
                    process_bluetooth_message(BluetoothMessage::PeerReadPassword, &mut rx, ui),
                )
                .await?;
                println!("Peer read password");
                Ok((peer_os, ssid, password))
            } else {
                // wait for peer to write its ssid and password
                let ssid = match with_progress(
                    ui,
                    "Waiting for the other device to send its details over Bluetooth",
                    process_bluetooth_message(BluetoothMessage::SSID("".to_string()), &mut rx, ui),
                )
                .await?
                {
                    BluetoothMessage::SSID(s) => s,
                    other => Err(FCError {
                        message: format!(
                            "Received unexpected BluetoothMessage when waiting for peer SSID: {:?}",
                            other
                        ),
                    })?,
                };
                println!("Peer's SSID: {}", ssid);
                let password = match with_progress(
                    ui,
                    "Waiting for the password over Bluetooth",
                    process_bluetooth_message(
                        BluetoothMessage::Password("".to_string()),
                        &mut rx,
                        ui,
                    ),
                )
                .await?
                {
                    BluetoothMessage::Password(p) => p,
                    other => Err(FCError {
                        message: format!(
                            "Received unexpected BluetoothMessage when waiting for peer password: {:?}",
                            other
                        ),
                    })?,
                };
                println!("Peer's password: {}", password);
                Ok((peer_os, ssid, password))
            }
        }
        .await;

        // Give the peer a moment to finish its final read before the service disappears.
        if exchange.is_ok() {
            sleep(Duration::from_secs(1)).await;
        }
        println!("Removing GATT service");
        drop(app_handle);

        // Hang up the BLE link, not just the service. Nothing on Linux disconnected before
        // this, in either role, so the ACL raised for the credential exchange outlived the
        // whole transfer. Two consequences, both observed on the next transfer in the reverse
        // direction (Linux->Android, then Android->Linux, 2026-07-25):
        //
        //   1. We come back as the *central* and find Device1.Connected already true, so
        //      find_characteristics takes its "already connected" arm and inherits whatever
        //      bearer that link happens to be. If the bond is dual-transport -- which is
        //      exactly what a bond made while we were the peripheral is, via CTKD -- that can
        //      be BR/EDR, which serves no GATT, and services() then burns bluer's 120s
        //      ServicesResolved timeout before failing.
        //   2. The peer keeps a live link to a device that just dropped its GATT service, so
        //      its cache of our database goes stale in place with no Service Changed to tell
        //      it otherwise.
        //
        // Disconnect() drops every bearer, so the next transfer starts from nothing. The bond
        // is untouched -- this is not remove_device (law 4 in docs/bluetooth-field-guide.md).
        let peer_address = *peer_address
            .lock()
            .expect("Could not lock peer address mutex");
        match peer_address {
            Some(addr) => match adapter.device(addr) {
                Ok(device) => match device.disconnect().await {
                    Ok(()) => println!("Disconnected BLE link to {}", addr),
                    Err(e) => println!("Could not disconnect BLE link to {}: {}", addr, e),
                },
                Err(e) => println!("Could not get device {} to disconnect: {}", addr, e),
            },
            // No characteristic request ever landed, so no link of ours to drop.
            None => println!("No BLE peer address recorded; nothing to disconnect"),
        }

        exchange
    } else {
        // acting as central
        ui.output("Started Bluetooth scan, waiting for sending device...");
        ui.output("The other device has to be advertising -- start the transfer there too.");
        // Two rungs, and the bond-destroying one is now the second rather than the first.
        //
        // This retry was written for a poisoned bond — classic-only or dual-transport, left
        // over from a pairing that went over BR/EDR — which makes BlueZ keep dialing the
        // wrong bearer. That is now handled without touching the bond: ensure_le_link()
        // raises the LE ACL link before every Connect() against a paired peer, which is what
        // actually fixed the Windows->Linux failure. So retry once as-is first; the bond
        // survives and, in the case this rung was built for, the retry now succeeds.
        //
        // remove_device is kept only as a last resort, because it is one-sided: the peer
        // keeps its half of the bond and cannot be told, which is the failure mode described
        // in docs/bluetooth-field-guide.md (law 4) and the bug fixed in 6039d53. Apple peers
        // cannot clear their half programmatically at all, so the user is told what to do.
        let mut attempt = 0;
        let (device, characteristics) = loop {
            let device = central::scan(&adapter, ui).await?;
            ui.output("Found device");
            match find_characteristics(&device, ui).await {
                Ok(c) => break (device, c),
                Err(e) => {
                    attempt += 1;
                    println!("    Device failed (attempt {}): {}", attempt, e);
                    // Drop the link before retrying. Without this the retry is bit-for-bit the
                    // same attempt: find_characteristics reports "Already connected", skips
                    // Connect(), and fails the same way, because nothing else on Linux ever
                    // disconnects. Both rounds of the Android->Linux hang looked identical for
                    // exactly this reason. Disconnect() drops every bearer, so the next round
                    // starts from no link and ensure_le_link picks LE.
                    if let Err(disconnect_error) = device.disconnect().await {
                        println!(
                            "    Could not disconnect before retry: {}",
                            disconnect_error
                        );
                    }
                    match attempt {
                        1 => {
                            // Say what went wrong, not just that something did: this is where a
                            // pairing that was never accepted on the other device surfaces, and
                            // the retry below is silent about it for another whole scan.
                            ui.output(&format!("Bluetooth connection failed: {}", e));
                            ui.output("Looking for the other device again...");
                        }
                        2 => {
                            // This used to remove the pairing here. It is exactly the one-sided
                            // unpairing law 4 of docs/bluetooth-field-guide.md forbids, and on
                            // 2026-08-08 it did the predictable damage: two failed rounds against
                            // a stale cache entry (fixed in central::scan) reached this rung, it
                            // deleted a bond that was working, and the next round's fresh pairing
                            // request arrived at a phone that still held its half -- which
                            // dropped its bond and reported "pairing failed". Keeping the bond
                            // costs nothing; a genuinely poisoned one is the user's to clear, on
                            // both devices, which is the only way it can be done safely.
                            ui.output(
                                "Bluetooth connection still failing. If it keeps failing, remove this computer from the other device's Bluetooth settings AND the other device from this computer's, then try again.",
                            );
                        }
                        _ => Err(e)?,
                    }
                }
            }
        };

        ui.output("Exchanging details over Bluetooth...");
        let info = exchange_info(characteristics, mode, ui, connection_mode).await;
        // Hang up, for the same reason the peripheral branch above does: on success every
        // write was a confirmed WriteOp::Request and every read has returned, so the
        // exchange is complete, and a link left up is one the next transfer inherits in the
        // opposite role. On failure the same applies with more force -- the error path used
        // to return with the link still up, which is exactly the inherited-link hazard (law
        // 9). The bond survives; only the link goes.
        match device.disconnect().await {
            Ok(()) => println!("Disconnected BLE link to {}", device.address()),
            Err(e) => println!(
                "Could not disconnect BLE link to {}: {}",
                device.address(),
                e
            ),
        }
        Ok(info?)
    }
}

// TODO: make linux-appropriate
pub async fn process_bluetooth_message<T: UI>(
    looking_for: BluetoothMessage,
    rx: &mut mpsc::Receiver<BluetoothMessage>,
    ui: &T,
) -> Result<BluetoothMessage, FCError> {
    loop {
        println!("waiting for bluetooth message...");
        let msg = rx
            .recv()
            .await
            .expect("Bluetooth message channel unexpectedly closed.");
        println!("received {:?}", msg);
        match &msg {
            BluetoothMessage::PairApproved => ui.output("Pairing approved."),
            BluetoothMessage::PairSuccess => {
                // can use this to represent AlreadyPaired on windows? don't need to emit pin, just need to proceed.
                // and nothing will be blocked in central because the pairing_handler won't be called.
                ui.output("Successfully paired");
            }
            BluetoothMessage::PairFailure => fc_error("Pairing failed.")?,
            BluetoothMessage::AlreadyPaired => {
                ui.output("Already BLE paired with Bluetooth device");
                if looking_for == BluetoothMessage::PairSuccess {
                    return Ok(msg);
                }
            }
            BluetoothMessage::UserCanceled => fc_error("User canceled.")?,
            BluetoothMessage::StartedAdvertising => {
                ui.output("Started advertising Bluetooth service")
            }
            BluetoothMessage::PeerOS(os) => ui.output(&format!("Peer's OS is {}", os)),
            BluetoothMessage::SSID(ssid) => ui.output(&format!("Peer's SSID is {}", ssid)),
            BluetoothMessage::Password(password) => {
                ui.output(&format!("Peer's password is {}", password))
            }
            BluetoothMessage::PeerReadSsid => ui.output("Peer read our SSID"),
            BluetoothMessage::PeerReadPassword => ui.output("Peer read our password"),
            BluetoothMessage::OtherError(s) => fc_error(s.as_str())?, // ui.output(&format!("Bluetooth peering result: {}", s)),
            other_message => println!(
                "Other Bluetooth message not used on Linux: {:?}",
                other_message
            ),
        };
        if discriminant(&msg) == discriminant(&looking_for) {
            return Ok(msg);
        }
    }
}
