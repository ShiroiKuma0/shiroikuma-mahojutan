mod central;
mod peripheral;

use bluer::{Adapter, Address, Session};
use central::{exchange_info, find_characteristics};
use std::{collections::HashSet, mem::discriminant, time::Duration};
use tokio::{
    spawn,
    sync::mpsc,
    time::{sleep, timeout},
};

use crate::{
    error::{fc_error, FCError},
    network::is_hosting,
    utils::{generate_password, get_key_and_ssid, BluetoothMessage},
    Mode, Peer, WiFiInterface, UI,
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
// const NO_SSID: &str = "NONE";

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
    _ble_ui_rx: mpsc::Receiver<bool>, // only used on windows
    interface: &WiFiInterface,
    ui: &T,
) -> Result<(String, String, String), FCError> {
    // TODO: dedup with check_support(), but can't return adapter from it because windows doesn't, unless we stub which is annoying to pass it back into this.
    let session = Session::new().await?;
    let adapter = session.default_adapter().await?;
    adapter.set_powered(true).await?;

    struct ConnectedPeripheral {
        adapter: Adapter,
        address: Address,
        is_macos: bool,
    }

    impl Drop for ConnectedPeripheral {
        fn drop(&mut self) {
            // We used to remove_device() here for every non-macOS peer, which deletes our pairing
            // keys. Android keeps its bond, so the two sides ended up disagreeing: the next
            // transfer's read of an ENCRYPTED_MITM characteristic restarted pairing, the phone
            // refused because it already held a bond for us, the link dropped ("Device
            // disconnected" on the phone) and our ReadValue never returned -- a 25s D-Bus timeout.
            // That made every second transfer fail, alternating, since the failure also destroyed
            // the phone's bond and left both sides clean again.
            //
            // Remove the device, keys and all, and let each transfer pair afresh. Keeping the bond
            // was tried twice and fails for a discovery reason rather than a key one: a paired peer
            // is never announced by BlueZ during discovery (DeviceAdded only fires for an object it
            // had pruned and sees again, and paired devices are never pruned), and the bonded
            // identity record does not carry our service UUID, so the by-address lookup skips it as
            // well. The peer clears its own bond at the same point -- CLEAR_BOND_AFTER_TRANSFER in
            // Bluetooth.kt -- and the two must stay in step: whichever side keeps its keys will
            // fail against the side that threw them away. macOS is exempt: it will not let Linux
            // enumerate its services when Linux initiates, so those pairings are managed by hand.
            if self.is_macos {
                return;
            }
            let adapter = self.adapter.clone();
            let address = self.address;
            spawn(async move {
                match adapter.remove_device(address).await {
                    Ok(_) => println!("Removed device {} (pairing cleared both sides)", address),
                    Err(e) => println!("Failed to unpair from peripheral: {}", e),
                };
            });
        }
    }

    if let Mode::Send(_) = mode {
        // acting as peripheral
        let (tx, mut rx) = mpsc::channel(1);
        let mut password = generate_password();
        let (_, mut ssid) = get_key_and_ssid(&password);
        let (app_handle, adv_handle) = peripheral::advertise(tx, &ssid, &password).await?;
        ui.output("Started Bluetooth advertisement, waiting for receiving device...");
        let peer_os =
            match process_bluetooth_message(BluetoothMessage::PeerOS("".to_string()), &mut rx, ui)
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
        ui.output("Receiving device found us, stopped advertising");
        drop(adv_handle);

        if is_hosting(&Peer::from(peer_os.as_str()), mode) {
            // wait for peer to read our ssid and password
            ui.output("Waiting for it to read our hotspot details...");
            process_bluetooth_message(BluetoothMessage::PeerReadSsid, &mut rx, ui).await?;
            println!("Peer read SSID");
            ui.output("It has the network name");
            process_bluetooth_message(BluetoothMessage::PeerReadPassword, &mut rx, ui).await?;
            println!("Peer read password");
            ui.output("It has the password too — starting our hotspot next");
        } else {
            // wait for peer to write its ssid and password
            ssid = match process_bluetooth_message(
                BluetoothMessage::SSID("".to_string()),
                &mut rx,
                ui,
            )
            .await?
            {
                BluetoothMessage::SSID(s) => s,
                other => Err(FCError {
                    message: format!(
                        "Received unexpected BluetoothMessage when waiting for peer OS: {:?}",
                        other
                    ),
                })?,
            };
            println!("Peer's SSID: {}", ssid);
            ui.output(&format!("Its hotspot is {}", ssid));
            password = match process_bluetooth_message(
                BluetoothMessage::Password("".to_string()),
                &mut rx,
                ui,
            )
            .await?
            {
                BluetoothMessage::Password(p) => p,
                other => Err(FCError {
                    message: format!(
                        "Received unexpected BluetoothMessage when waiting for peer OS: {:?}",
                        other
                    ),
                })?,
            };
            println!("Peer's password: {}", password);
            ui.output("Got its password — joining that hotspot next");
        }

        sleep(Duration::from_secs(1)).await;
        println!("Removing GATT service");
        drop(app_handle);

        Ok((peer_os, ssid, password))
    } else {
        // acting as central
        ui.output("Started Bluetooth scan, waiting for sending device...");

        // A device that turns out not to be the peer is not a failure of the transfer -- it is
        // just some other Bluetooth device in the room. Go back to scanning instead of aborting,
        // otherwise one paired headset nearby ends the transfer before it starts.
        // Any nearby phone that has ever run this app keeps our service UUID in BlueZ's record of
        // it, and neither "paired" nor "connected" nor RSSI tells it apart from the peer that is
        // actually advertising for us right now. So stop trying to identify the right device up
        // front and make picking the wrong one cheap: give each candidate a bounded attempt, and on
        // any failure -- including a hang, which is what connecting to the wrong phone looks like --
        // drop it, remember it, and take the next one. At most a couple of candidates exist.
        let mut rejected = HashSet::new();
        let (device, info) = loop {
            let device = central::scan(&adapter, &rejected).await?;
            ui.output("Found device");
            let address = device.address();
            // Generous on purpose: the first read of an ENCRYPTED_MITM characteristic starts a
            // pairing that needs a passkey typed on BOTH devices, and 30s expired while the user
            // was still entering it -- then the disconnect below tore down the half-made bond. A
            // wrong device does not need this long; it fails with an error in a second or two, and
            // only a genuine hang spends the whole budget.
            let attempt = timeout(Duration::from_secs(180), async {
                let characteristics = find_characteristics(&device, ui).await?;
                exchange_info(characteristics, mode, &interface.0, ui).await
            })
            .await;
            match attempt {
                Ok(Ok(info)) => break (device, info),
                Ok(Err(e)) => {
                    println!("    Device {} failed: {}. Resuming scan.", address, e);
                    ui.output("Device was not the peer, still scanning...");
                    let _ = device.disconnect().await;
                    rejected.insert(address);
                }
                Err(_) => {
                    println!("    Device {} did not answer in time. Resuming scan.", address);
                    ui.output("Device did not answer, still scanning...");
                    let _ = device.disconnect().await;
                    rejected.insert(address);
                }
            }
        };

        let mut connected_peripheral = ConnectedPeripheral {
            adapter,
            address: device.address(),
            is_macos: false,
        };
        connected_peripheral.is_macos = info.0 == "mac".to_string();
        Ok(info)
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
