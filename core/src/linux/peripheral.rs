use crate::{
    bluetooth::{
        OS, OS_CHARACTERISTIC_UUID, PASSWORD_CHARACTERISTIC_UUID, SERVICE_UUID,
        SSID_CHARACTERISTIC_UUID,
    },
    utils::BluetoothMessage,
    UI,
};

use bluer::{
    adv::{Advertisement, AdvertisementHandle},
    gatt::local::{
        Application, ApplicationHandle, Characteristic, CharacteristicRead, CharacteristicWrite,
        CharacteristicWriteMethod, ReqError, Service,
    },
    Adapter, Address, Uuid,
};
use futures::FutureExt;
use std::{
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::sync::mpsc;

/// The address of the central that drove this transfer, filled in by the first characteristic
/// request. `negotiate_bluetooth` uses it to hang up the BLE link when the exchange is done —
/// see the disconnect there for why leaving it up breaks the *next* transfer.
pub(crate) type PeerSlot = Arc<Mutex<Option<Address>>>;

// Direction A (macOS central -> Linux peripheral): after the central bonds, mark it
// trusted so BlueZ keeps the bond and can resolve macOS's rotating (RPA) address on
// future transfers via the stored IRK — this is what stops the recurring CBError 14
// "Peer removed pairing information" on the macOS side. The GATT request callbacks are
// the precise place to do it: our characteristics require an encrypted link, so by the
// time a request arrives the peer has bonded, and only our actual peer (never some
// bystander device) touches the characteristics. Do NOT remove_device this peer on
// cleanup.
async fn trust_peer(adapter: &Adapter, peer: &PeerSlot, address: Address) {
    // Record who we're talking to before the early return below, so the address is captured
    // on repeat transfers with an already-trusted peer too.
    *peer.lock().expect("Could not lock peer address mutex") = Some(address);
    let device = match adapter.device(address) {
        Ok(device) => device,
        Err(e) => {
            println!("Could not get device {} to mark it trusted: {}", address, e);
            return;
        }
    };
    if device.is_trusted().await.unwrap_or(false) {
        return;
    }
    match device.set_trusted(true).await {
        Ok(()) => println!(
            "Marked {} trusted so BlueZ keeps the bond for future transfers",
            address
        ),
        Err(e) => println!("Could not mark {} trusted: {}", address, e),
    }
}

fn get_os_characteristic(
    adapter: Adapter,
    tx: mpsc::Sender<BluetoothMessage>,
    peer: PeerSlot,
) -> Characteristic {
    // when the OS characteristic is read, return the constant
    // when it's written to, return that to calling thread, so we need tx
    let write_tx = tx.clone();
    let read_adapter = adapter.clone();
    let write_adapter = adapter;
    let read_peer = peer.clone();
    let write_peer = peer;
    Characteristic {
        uuid: Uuid::parse_str(OS_CHARACTERISTIC_UUID).unwrap(),
        read: Some(CharacteristicRead {
            read: true,
            secure_read: true,
            // so this is a pub type CharacteristicReadFun = Box<dyn Fn(CharacteristicReadRequest) -> Pin<Box<dyn Future<Output = ReqResult<Vec<u8>>> + Send>> + Send + Sync>;
            // a box containing function, that takes a characteristicreadrequest, and returns a pin box containing an async future, that returns a byte vec
            fun: Box::new(move |req| {
                let adapter = read_adapter.clone();
                let peer = read_peer.clone();
                async move {
                    let value = OS.as_bytes().to_vec();
                    println!("Read request {:?} with value {:x?}", &req, &value);
                    trust_peer(&adapter, &peer, req.device_address).await;
                    Ok(value)
                }
                .boxed()
            }),
            ..Default::default()
        }),
        write: Some(CharacteristicWrite {
            write: true,
            write_without_response: false, // TODO: remove?
            secure_write: true,
            method: CharacteristicWriteMethod::Fun(Box::new(move |new_value, req| {
                // let value = value_write.clone();
                let thread_tx = write_tx.clone();
                let adapter = write_adapter.clone();
                let peer = write_peer.clone();
                async move {
                    println!("Write request {:?} with value {:x?}", &req, &new_value);
                    trust_peer(&adapter, &peer, req.device_address).await;
                    let peer_os = String::from_utf8(new_value).expect("Peer OS was not UTF-8");
                    if thread_tx
                        .send(BluetoothMessage::PeerOS(peer_os))
                        .await
                        .is_err()
                    {
                        return Err(ReqError::Failed);
                    }
                    Ok(())
                }
                .boxed()
            })),
            ..Default::default()
        }),
        ..Default::default()
    }
}

fn get_ssid_characteristic(tx: mpsc::Sender<BluetoothMessage>, ssid: String) -> Characteristic {
    let read_tx = tx.clone();
    let write_tx = tx.clone();
    Characteristic {
        uuid: Uuid::parse_str(SSID_CHARACTERISTIC_UUID).unwrap(),
        read: Some(CharacteristicRead {
            read: true,
            secure_read: true,
            fun: Box::new(move |req| {
                let ssid = ssid.clone();
                let thread_tx = read_tx.clone();
                async move {
                    let value = ssid.as_bytes().to_vec();
                    println!("Read request {:?} with value {:x?}", &req, &value);
                    if thread_tx
                        .send(BluetoothMessage::PeerReadSsid)
                        .await
                        .is_err()
                    {
                        return Err(ReqError::Failed);
                    }
                    Ok(value)
                }
                .boxed()
            }),
            ..Default::default()
        }),
        write: Some(CharacteristicWrite {
            write: true,
            write_without_response: false,
            secure_write: true,
            method: CharacteristicWriteMethod::Fun(Box::new(move |new_value, req| {
                let thread_tx = write_tx.clone();
                async move {
                    println!("Write request {:?} with value {:x?}", &req, &new_value);
                    let peer_ssid = String::from_utf8(new_value).expect("Peer OS was not UTF-8");
                    if thread_tx
                        .send(BluetoothMessage::SSID(peer_ssid))
                        .await
                        .is_err()
                    {
                        return Err(ReqError::Failed);
                    }
                    Ok(())
                }
                .boxed()
            })),
            ..Default::default()
        }),
        ..Default::default()
    }
}

fn get_password_characteristic(
    tx: mpsc::Sender<BluetoothMessage>,
    password: String,
) -> Characteristic {
    let read_tx = tx.clone();
    let write_tx = tx.clone();
    Characteristic {
        uuid: Uuid::parse_str(PASSWORD_CHARACTERISTIC_UUID).unwrap(),
        read: Some(CharacteristicRead {
            read: true,
            secure_read: true,
            fun: Box::new(move |req| {
                let password = password.clone();
                let thread_tx = read_tx.clone();
                async move {
                    let value = password.as_bytes().to_vec();
                    println!("Read request {:?} with value {:x?}", &req, &value);
                    if thread_tx
                        .send(BluetoothMessage::PeerReadPassword)
                        .await
                        .is_err()
                    {
                        return Err(ReqError::Failed);
                    }
                    Ok(value)
                }
                .boxed()
            }),
            ..Default::default()
        }),
        write: Some(CharacteristicWrite {
            write: true,
            write_without_response: false,
            secure_write: true,
            method: CharacteristicWriteMethod::Fun(Box::new(move |new_value, req| {
                let thread_tx = write_tx.clone();
                async move {
                    println!("Write request {:?} with value {:x?}", &req, &new_value);
                    let peer_password =
                        String::from_utf8(new_value).expect("Peer OS was not UTF-8");
                    if thread_tx
                        .send(BluetoothMessage::Password(peer_password))
                        .await
                        .is_err()
                    {
                        return Err(ReqError::Failed);
                    }
                    Ok(())
                }
                .boxed()
            })),
            ..Default::default()
        }),
        ..Default::default()
    }
}

// Takes the adapter from negotiate_bluetooth's session (rather than opening its own)
// so that the pairing agent registered there is on the same D-Bus connection that
// serves this GATT application — the agent's lifetime then necessarily covers any
// pairing triggered while advertising.

/// Register the advertisement and report whether it actually took a slot.
///
/// Split out so the wait below can put it back: BlueZ has been observed accepting the
/// registration -- the active-instance count goes 0 -> 1 -- and then dropping it again while the
/// app sits waiting, at which point this side believes it is advertising and no scanner in the
/// room can hear it (白い熊, 2026-08-08: the phone's own scan logged two neighbours and no PC).
pub(crate) async fn start_advertisement<T: UI>(
    adapter: &Adapter,
    ui: &T,
) -> bluer::Result<AdvertisementHandle> {
    let service_uuid = Uuid::parse_str(SERVICE_UUID).unwrap();
    // No local name. An advertisement is 31 bytes: 3 for the flags and 18 for our 128-bit service
    // UUID leave 10, and "Flying Carpet" needs 15 with its header -- so the packet overflows and
    // BlueZ has to shuffle something out of it. What the peer filters on is the service UUID, and
    // a UUID that ends up in the scan response (or is dropped) is a peer that is advertising and
    // cannot be found.
    let le_advertisement = Advertisement {
        service_uuids: vec![service_uuid].into_iter().collect(),
        discoverable: Some(true),
        // Advertise ten times faster than BlueZ's default. Its default for a discoverable
        // advertisement is 1280 ms, and Android's LOW_POWER scan listens for 512 ms every
        // 5120 ms -- exactly four times 1280. Two harmonically related periods means the phase
        // between them never drifts: land in the gap once and the advertisement stays in the gap
        // for ever, so the phone hears neighbours three rooms away and not the machine on the
        // same desk. That is the "it worked perfectly, now it never works" of 2026-08-08, and it
        // was luck either way. 150 ms is not a harmonic of anything the peer does and gives any
        // scan window several chances.
        min_interval: Some(Duration::from_millis(100)),
        max_interval: Some(Duration::from_millis(150)),
        ..Default::default()
    };
    let before = adapter.active_advertising_instances().await.unwrap_or(0);
    let handle = adapter.advertise(le_advertisement).await?;
    let after = adapter.active_advertising_instances().await.unwrap_or(0);
    println!(
        "Advertising service {} without a local name (31-byte budget); active instances {} -> {} of {}",
        service_uuid,
        before,
        after,
        adapter.supported_advertising_instances().await.unwrap_or(0),
    );
    if after <= before {
        ui.output(
            "Bluetooth accepted our advertisement but is not broadcasting it -- something else on \
             this computer is holding the advertising slot. Turning Bluetooth off and on again \
             clears it.",
        );
    }
    Ok(handle)
}

pub(crate) async fn advertise<T: UI>(
    adapter: &Adapter,
    tx: mpsc::Sender<BluetoothMessage>,
    ssid: &str,
    password: &str,
    ui: &T,
) -> bluer::Result<(ApplicationHandle, AdvertisementHandle, PeerSlot)> {
    let service_uuid = Uuid::parse_str(SERVICE_UUID).unwrap();
    // Accept incoming pairing so a central (e.g. macOS) can bond to read our encrypted
    // characteristics. Combined with the agent registered in negotiate_bluetooth, this lets
    // pairing complete during the transfer instead of requiring a manual system pairing.
    adapter.set_pairable(true).await?;

    println!(
        "Advertising on Bluetooth adapter {} with address {}",
        adapter.name(),
        adapter.address().await?
    );
    let adv_handle = start_advertisement(adapter, ui).await?;

    println!(
        "Serving GATT service on Bluetooth adapter {}",
        adapter.name()
    );

    let peer: PeerSlot = Arc::new(Mutex::new(None));
    let characteristics = vec![
        get_os_characteristic(adapter.clone(), tx.clone(), peer.clone()),
        get_ssid_characteristic(tx.clone(), ssid.to_string()),
        get_password_characteristic(tx, password.to_string()),
    ];

    let app = Application {
        services: vec![Service {
            uuid: service_uuid,
            primary: true,
            characteristics,
            ..Default::default()
        }],
        ..Default::default()
    };
    let app_handle = adapter.serve_gatt_application(app).await?;
    Ok((app_handle, adv_handle, peer))
}
