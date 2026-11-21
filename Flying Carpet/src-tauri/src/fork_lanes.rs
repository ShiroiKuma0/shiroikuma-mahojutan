// Fork: parallel paired transfers — the desktop's lanes.
//
// The rules live in core (`lanes::LaneBoard`), where they are tested. This module is what
// hangs real transfers on them: the task behind each lane, its cancel handle, the channel
// its file-conflict answers come back on, and the selection a queued send is holding on to.
//
// Only paired transfers over the shared network come through here. Hotspot and
// password-mode transfers keep upstream's single slot (`Transfer`) and its un-tagged window
// events, untouched — the two are exclusive, and `primary_busy` / `LanesState::is_active`
// are the two halves of that.
//
// Every lane speaks to the window through a `LaneUi`, which is the core's `UI` trait with
// the lane's id on every event and the peer's name in front of every log line. That is the
// whole of the coupling: sending.rs and receiving.rs do not know lanes exist.

use crate::fork_paired;
use flying_carpet_core::{
    lanes::{Lane, LaneBoard, LaneId, LaneRefusal, SendAdmission},
    FileConflictAnswer, FileConflictChoice, SendFile, Transfer, UI,
};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, oneshot};

/// What a send is holding while it waits for its turn: its own selection, fixed at the
/// moment it was asked for, so picking other files meanwhile cannot change it.
pub struct PendingSend {
    pub device_id: String,
    pub files: Vec<SendFile>,
}

#[derive(Default)]
struct Runtime {
    cancel: Option<oneshot::Sender<()>>,
    conflict_tx: Option<mpsc::Sender<FileConflictAnswer>>,
    pending: Option<PendingSend>,
}

#[derive(Default)]
struct Inner {
    board: LaneBoard,
    runtime: HashMap<LaneId, Runtime>,
    /// The last send to each device that did not finish — cancelled here, cancelled there, or
    /// cut — and when. It is what the phone's narrow-channel dialog means by "Restart the
    /// transfer" when the files were this device's to send (`resend`).
    unfinished: HashMap<String, (std::time::Instant, Vec<SendFile>)>,
}

/// How long an unfinished send stays restartable. Long enough for a Wi-Fi reconnect and a
/// moment's thought; short enough that "restart" never means something from this morning.
const UNFINISHED_KEEP: std::time::Duration = std::time::Duration::from_secs(600);

#[derive(Default)]
pub struct LanesState {
    inner: Mutex<Inner>,
}

impl LanesState {
    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Any lane at all, running or waiting. Asked by start_async, under the transfer-task
    /// lock, before it lets a hotspot or password-mode transfer begin.
    pub fn is_active(&self) -> bool {
        self.lock().board.is_active()
    }
}

/// What start_async says when it refuses for a lane's sake.
pub const PRIMARY_REFUSED: &str = "A transfer with a paired device is running or waiting. \
    Hotspot and password transfers need the network to themselves — start this one when \
    those have finished.";

#[derive(Clone, serde::Serialize)]
struct LanePayload {
    lane: LaneId,
}

#[derive(Clone, serde::Serialize)]
struct LaneValue {
    lane: LaneId,
    value: u8,
}

#[derive(Clone, serde::Serialize)]
struct LaneDetails {
    lane: LaneId,
    current: String,
    total: String,
}

#[derive(Clone, serde::Serialize)]
struct LaneConflict {
    lane: LaneId,
    peer: String,
    name: String,
    local_size: u64,
    incoming_size: u64,
    identical: bool,
    more_files: bool,
}

#[derive(Clone, serde::Serialize)]
struct Message {
    message: String,
}

/// One lane's voice in the window. Best-effort throughout, like `BackgroundUi`: a lane
/// outlives nothing, but the window can go away under it and an emit must never panic.
///
/// A receiving connection starts *unbound* — it is not a lane until its offer has been read
/// and accepted, and a stranger turned away at the hello never becomes one. Until then its
/// few lines go to the log bare and it has no progress to report.
#[derive(Clone)]
pub struct LaneUi {
    app: AppHandle,
    bound: Arc<Mutex<Option<(LaneId, String)>>>,
}

impl LaneUi {
    fn unbound(app: AppHandle) -> Self {
        LaneUi {
            app,
            bound: Arc::new(Mutex::new(None)),
        }
    }

    fn for_lane(app: AppHandle, id: LaneId, peer_name: &str) -> Self {
        LaneUi {
            app,
            bound: Arc::new(Mutex::new(Some((id, peer_name.to_string())))),
        }
    }

    fn lane(&self) -> Option<(LaneId, String)> {
        self.bound.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }
}

impl UI for LaneUi {
    fn output(&self, msg: &str) {
        let line = match self.lane() {
            Some((_, name)) => format!("[{}] {}", name, msg),
            None => msg.to_string(),
        };
        println!("[paired] {}", line);
        let _ = self.app.emit("outputMsg", Message { message: line });
    }
    fn show_progress_bar(&self) {
        if let Some((lane, _)) = self.lane() {
            let _ = self.app.emit("laneProgress", LaneValue { lane, value: 0 });
        }
    }
    fn update_progress_bar(&self, percent: u8) {
        if let Some((lane, _)) = self.lane() {
            let _ = self.app.emit("laneProgress", LaneValue { lane, value: percent });
        }
    }
    fn update_total_progress_bar(&self, percent: u8) {
        if let Some((lane, _)) = self.lane() {
            let _ = self
                .app
                .emit("laneTotalProgress", LaneValue { lane, value: percent });
        }
    }
    fn update_progress_details(&self, current: &str, total: &str) {
        if let Some((lane, _)) = self.lane() {
            let _ = self.app.emit(
                "laneDetails",
                LaneDetails {
                    lane,
                    current: current.to_string(),
                    total: total.to_string(),
                },
            );
        }
    }
    fn enable_ui(&self) {
        // Nothing to hand back: a lane never took the controls. Its block is put away by
        // `laneEnded`, which the lane's guard sends however the lane ends.
    }
    fn show_pin(&self, _: &str) {}
    fn ask_file_conflict(
        &self,
        name: &str,
        local_size: u64,
        incoming_size: u64,
        identical: bool,
        more_files: bool,
    ) {
        if let Some((lane, peer)) = self.lane() {
            let _ = self.app.emit(
                "laneFileConflict",
                LaneConflict {
                    lane,
                    peer,
                    name: name.to_string(),
                    local_size,
                    incoming_size,
                    identical,
                    more_files,
                },
            );
        }
    }
}

/// Whether a hotspot or password-mode transfer holds the primary slot. The guard is returned
/// so the caller can keep it across its own admission: start_async takes the same lock and
/// then asks `is_active`, and holding it here is what stops the two passing each other.
fn primary_busy<'a>(
    transfer: &'a State<'_, Transfer>,
) -> (bool, std::sync::MutexGuard<'a, flying_carpet_core::TransferTask>) {
    let task = transfer.task.lock().unwrap_or_else(|e| e.into_inner());
    (task.is_running() || task.cancelling, task)
}

/// Ends a lane however its task ended — finished, failed, cancelled, or panicked — because
/// it runs from `Drop`. Frees the lane, tells the window, and starts whatever was waiting
/// for it.
struct LaneGuard {
    app: AppHandle,
    ui: LaneUi,
}

impl Drop for LaneGuard {
    fn drop(&mut self) {
        if let Some((id, _)) = self.ui.lane() {
            end_lane(&self.app, id);
        }
    }
}

fn end_lane(app: &AppHandle, id: LaneId) {
    let lanes: State<LanesState> = app.state();
    let started: Vec<(Lane, PendingSend)> = {
        let mut inner = lanes.lock();
        inner.runtime.remove(&id);
        inner
            .board
            .finish(id)
            .into_iter()
            .filter_map(|next| {
                let lane = inner.board.get(next)?.clone();
                let pending = inner.runtime.get_mut(&next)?.pending.take()?;
                Some((lane, pending))
            })
            .collect()
    };
    let _ = app.emit("laneEnded", LanePayload { lane: id });
    for (lane, pending) in started {
        let _ = app.emit("laneStarted", LanePayload { lane: lane.id });
        spawn_send(app.clone(), lane.id, lane.peer_name, pending);
    }
}

/// Resolves only when the lane is really cancelled. A dropped sender is not a cancel — it
/// is what happens to a connection that never became a lane — so it waits for ever instead.
async fn cancelled(rx: oneshot::Receiver<()>) {
    if rx.await.is_err() {
        std::future::pending::<()>().await;
    }
}

// ── sending ───────────────────────────────────────────────────────────────────────────────

/// Opens a lane for a send, or queues it. Returns at once either way: the transfer runs on
/// a task of its own and reports through its lane.
pub fn request_send(
    app: &AppHandle,
    device_id: String,
    peer_name: String,
    files: Vec<SendFile>,
) -> Result<(), String> {
    let transfer: State<Transfer> = app.state();
    let lanes: State<LanesState> = app.state();
    let pending = PendingSend {
        device_id: device_id.clone(),
        files,
    };
    let admission = {
        let (busy, _task) = primary_busy(&transfer);
        let mut inner = lanes.lock();
        match inner.board.request_send(&device_id, &peer_name, busy) {
            Ok(SendAdmission::Run(id)) => {
                inner.runtime.insert(id, Runtime::default());
                Ok((id, Some(pending)))
            }
            Ok(SendAdmission::Queue(id)) => {
                inner.runtime.insert(
                    id,
                    Runtime {
                        pending: Some(pending),
                        ..Runtime::default()
                    },
                );
                Ok((id, None))
            }
            Err(_) => Err(
                "A hotspot or password transfer is running, and it needs the network to \
                 itself. Send to a paired device once it has finished."
                    .to_string(),
            ),
        }
    };
    let (id, run_now) = admission?;
    let lane = lanes.lock().board.get(id).cloned();
    if let Some(lane) = lane {
        let _ = app.emit("laneOpened", lane);
    }
    match run_now {
        Some(pending) => spawn_send(app.clone(), id, peer_name, pending),
        None => LaneUi::for_lane(app.clone(), id, &peer_name)
            .output("Queued. It starts when the transfer before it ends."),
    }
    Ok(())
}

fn spawn_send(app: AppHandle, id: LaneId, peer_name: String, pending: PendingSend) {
    let (cancel_tx, cancel_rx) = oneshot::channel();
    // Answers to "the other device already has this file", one question at a time.
    let (conflict_tx, mut conflict_rx) = mpsc::channel(1);
    {
        let lanes: State<LanesState> = app.state();
        let mut inner = lanes.lock();
        let runtime = inner.runtime.entry(id).or_default();
        runtime.cancel = Some(cancel_tx);
        runtime.conflict_tx = Some(conflict_tx);
    }
    tokio::spawn(async move {
        let ui = LaneUi::for_lane(app.clone(), id, &peer_name);
        let _guard = LaneGuard {
            app: app.clone(),
            ui: ui.clone(),
        };
        let finished = tokio::select! {
            result = fork_paired::run_send(
                &app,
                &ui,
                &pending.device_id,
                &pending.files,
                &mut conflict_rx,
            ) => {
                if let Err(e) = &result {
                    ui.output(&format!("Transfer error: {}", e));
                }
                result.is_ok()
            }
            _ = cancelled(cancel_rx) => {
                ui.output("Transfer cancelled.");
                false
            }
        };
        let lanes: State<LanesState> = app.state();
        let mut inner = lanes.lock();
        if finished {
            inner.unfinished.remove(&pending.device_id);
        } else {
            inner.unfinished.insert(
                pending.device_id.clone(),
                (std::time::Instant::now(), pending.files.clone()),
            );
        }
    });
}

/// Sends again what the last unfinished send to `device_id` was sending. Returns how many
/// files that is.
pub fn resend(app: &AppHandle, device_id: &str, peer_name: &str) -> Result<usize, String> {
    let files = {
        let lanes: State<LanesState> = app.state();
        let mut inner = lanes.lock();
        match inner.unfinished.remove(device_id) {
            Some((when, files)) if when.elapsed() <= UNFINISHED_KEEP => files,
            _ => return Err("There is no interrupted transfer to send again.".to_string()),
        }
    };
    let count = files.len();
    request_send(app, device_id.to_string(), peer_name.to_string(), files)?;
    Ok(count)
}

/// Whether any lane belongs to a device other than `device_id`. Asked before this device's
/// Wi-Fi is reconnected for one peer's sake: that takes every other peer's transfer with it.
pub fn others_active(app: &AppHandle, device_id: &str) -> bool {
    let lanes: State<LanesState> = app.state();
    let inner = lanes.lock();
    inner.board.list().iter().any(|lane| lane.peer_id != device_id)
}

// ── receiving ─────────────────────────────────────────────────────────────────────────────

/// What a connection carries until its offer is decided: the lane's voice, still unbound,
/// and the cancel handle that becomes the lane's if it is admitted.
pub struct Incoming {
    ui: LaneUi,
    cancel: Mutex<Option<oneshot::Sender<()>>>,
}

/// Turns an accepted offer into a lane, or says why not. Called from `decide`, after the
/// stored checks have passed, so a lane is never opened for an offer about to be refused.
pub fn admit_receive(
    app: &AppHandle,
    incoming: &Incoming,
    peer_id: &str,
    peer_name: &str,
) -> Result<(), LaneRefusal> {
    let transfer: State<Transfer> = app.state();
    let lanes: State<LanesState> = app.state();
    let lane = {
        let (busy, _task) = primary_busy(&transfer);
        let mut inner = lanes.lock();
        let id = inner.board.admit_receive(peer_id, peer_name, busy)?;
        inner.runtime.insert(
            id,
            Runtime {
                cancel: incoming
                    .cancel
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .take(),
                ..Runtime::default()
            },
        );
        inner.board.get(id).cloned()
    };
    if let Some(lane) = lane {
        *incoming
            .ui
            .bound
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some((lane.id, lane.peer_name.clone()));
        let _ = app.emit("laneOpened", lane);
    }
    Ok(())
}

/// One accepted connection, on a task of its own. The listener hands it over and goes
/// straight back to accepting.
pub fn spawn_receive(app: AppHandle, tcp: TcpStream, addr: SocketAddr) {
    let (cancel_tx, cancel_rx) = oneshot::channel();
    let incoming = Arc::new(Incoming {
        ui: LaneUi::unbound(app.clone()),
        cancel: Mutex::new(Some(cancel_tx)),
    });
    tokio::spawn(async move {
        let ui = incoming.ui.clone();
        let _guard = LaneGuard {
            app: app.clone(),
            ui: ui.clone(),
        };
        tokio::select! {
            result = fork_paired::serve_connection(&app, tcp, &ui, incoming.clone()) => {
                if let Err(e) = result {
                    // Includes the ordinary case of a stranger connecting: they cannot get
                    // past the hello without a key of ours, and this is where that surfaces.
                    ui.output(&format!("Transfer from {} ended: {}", addr, e));
                }
            }
            _ = cancelled(cancel_rx) => ui.output("Transfer cancelled."),
        }
    });
}

// ── commands ──────────────────────────────────────────────────────────────────────────────

/// Every lane as it stands, for a window that has just been (re)loaded.
#[tauri::command]
pub fn lanes_status(lanes: State<LanesState>) -> Vec<Lane> {
    lanes.lock().board.list()
}

/// Cancels one lane and nothing else. A running lane is stopped where it stands — its
/// socket closes, which is how the far device learns — and a queued one simply never starts.
#[tauri::command]
pub fn lane_cancel(app: AppHandle, lanes: State<LanesState>, lane: LaneId) {
    enum Found {
        Running(oneshot::Sender<()>),
        Queued(String),
        Gone,
    }
    let found = {
        let mut inner = lanes.lock();
        match inner.board.get(lane).cloned() {
            Some(info) if info.queued => Found::Queued(info.peer_name),
            Some(_) => match inner.runtime.get_mut(&lane).and_then(|r| r.cancel.take()) {
                Some(cancel) => Found::Running(cancel),
                // Already cancelling, or in the instant before its task has its handle.
                None => Found::Gone,
            },
            None => Found::Gone,
        }
    };
    match found {
        // The lane's own task says "Transfer cancelled." and its guard does the rest.
        Found::Running(cancel) => {
            let _ = cancel.send(());
        }
        Found::Queued(peer_name) => {
            LaneUi::for_lane(app.clone(), lane, &peer_name).output("Queued transfer cancelled.");
            end_lane(&app, lane);
        }
        Found::Gone => {}
    }
}

/// The window's answer to one lane's file-conflict question. The twin of
/// user_file_conflict, addressed.
#[tauri::command]
pub fn lane_file_conflict(
    lanes: State<LanesState>,
    lane: LaneId,
    choice: String,
    new_name: Option<String>,
    apply_to_all: bool,
) {
    let answer = FileConflictAnswer {
        choice: match choice.as_str() {
            "overwrite" => FileConflictChoice::Overwrite,
            "rename" => FileConflictChoice::Rename(new_name.unwrap_or_default()),
            _ => FileConflictChoice::Skip,
        },
        apply_to_all,
    };
    let conflict_tx = lanes
        .lock()
        .runtime
        .get(&lane)
        .and_then(|r| r.conflict_tx.clone());
    if let Some(conflict_tx) = conflict_tx {
        tokio::spawn(async move {
            // The lane may have ended while the dialog was up; answering it then is a no-op.
            let _ = conflict_tx.send(answer).await;
        });
    }
}
