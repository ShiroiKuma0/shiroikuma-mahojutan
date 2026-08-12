use crate::{error::fc_error, utils, FCError, UI};
use core::time;
use std::{
    fs,
    io::Write,
    path::{Component, Path, PathBuf},
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    time::{sleep, timeout},
};

// Sanity bounds on peer-supplied header values, checked before they're used to size an
// allocation (see also MAX_FILE_COUNT in lib.rs). Chosen so every legitimate transfer
// passes — Apple and Android senders use 5MB chunks, ours are CHUNKSIZE (1MB) — while
// absurd values, which are memory-exhaustion levers, are rejected as corrupt/hostile.
const MAX_FILENAME_BYTES: u64 = 8192;
const MAX_CHUNK_BYTES: u64 = 5_000_000;

// v10+: file contents are protected by the Noise transport (see core/src/noise.rs), which
// wraps the whole connection, so chunks arrive as raw bytes here — no application-level
// decryption.
pub async fn receive_file<S: AsyncRead + AsyncWrite + Unpin, T: UI>(
    folder: &Path,
    stream: &mut S,
    totals: &mut crate::Totals,
    ui: &T,
    last_file: bool,
    peer_is_fork: bool,
) -> Result<(), FCError> {
    let folder = folder.to_owned();

    // check destination folder
    fs::read_dir(&folder)?;

    // receive file details
    let (filename, file_size) = receive_file_details(stream).await?;
    ui.output(&format!("Filename: {}", filename));
    ui.output(&format!(
        "File size: {}",
        utils::make_size_readable(file_size)
    ));
    let mut bytes_left = file_size;

    // see if we already have the file being sent
    let relative_path = sanitize_relative_filename(&filename)?;
    let mut full_path = folder.clone();
    full_path.push(&relative_path);
    // Fork-only: report what we have and let the sending device decide (see FileConflictChoice).
    // Upstream's rule -- skip an identical file, silently rename a differing one -- is made here,
    // on the device whose user is not the one watching the transfer.
    let mut overwrite = false;
    if peer_is_fork {
        match resolve_conflict(&full_path, file_size, stream).await? {
            ConflictOutcome::Skip => {
                ui.output("The sending device chose to skip this file.");
                return Ok(());
            }
            ConflictOutcome::Overwrite => {
                ui.output("Replacing the copy we already had.");
                overwrite = true;
            }
            ConflictOutcome::Receive(new_name) => {
                if let Some(name) = new_name {
                    let renamed = sanitize_relative_filename(&name)?;
                    full_path = folder.clone();
                    full_path.push(&renamed);
                    ui.output(&format!("Saving it as \"{}\".", renamed.to_string_lossy()));
                }
            }
        }
    } else {
        let need_transfer = check_for_file(&full_path, file_size, stream).await?;
        if !need_transfer {
            ui.output("Recipient already has this file, skipping.");
            return Ok(());
        }
    }

    // make parent directories if necessary
    utils::make_parent_directories(&full_path)?;

    // check if file being received already exists. if so, find new filename.
    // Overwriting is the one case that keeps the path it was given.
    let mut i = 1;
    while !overwrite && full_path.is_file() {
        let file_name = full_path
            .file_name()
            .expect("could not get filename from full path")
            .to_str()
            .expect("could not convert filename to str");
        let new_name = format!("({}) ", i) + file_name;
        full_path.pop();
        full_path.push(new_name);
        i += 1;
    }

    // Receive into "<name>.part" and rename only once the file is whole.
    //
    // Writing straight to the final name meant a cancelled or dropped transfer left a truncated
    // file wearing the real filename -- indistinguishable from a complete one, and quietly wrong
    // months later when it is opened (白い熊, 2026-08-11, cancelling a 4 GB video mid-flight).
    // A ".part" suffix cannot be mistaken for the finished article, and the rename is atomic
    // within a filesystem, so the final name never exists in a partial state.
    let part_path = {
        let mut p = full_path.clone().into_os_string();
        p.push(".part");
        PathBuf::from(p)
    };
    let mut out_file = fs::File::create(&part_path)?;

    // show progress bar
    ui.show_progress_bar();
    let (total_pct, total_text) = totals.snapshot(0, file_size);
    ui.update_total_progress_bar(total_pct);
    ui.update_progress_details(&utils::progress_details(0, file_size, 0.0), &total_text);
    let mut last_details = Instant::now();
    // Recent-rate window; see utils::RateWindow.
    let mut rate = utils::RateWindow::new();
    // The clock for the *rate*, as opposed to `start`, which has been running since this function
    // was entered. Between the two sits the file-conflict exchange, and when the peer already has
    // the file that means both ends hash it end to end first -- tens of seconds on a multi-
    // gigabyte file, with not a byte of it moving. Dividing the file size by that whole span
    // reported a 3.5GB transfer at 214mbps while the wire had sustained about 280 (白い熊,
    // 2026-08-11). "How long did this take" and "how fast did it go" are different questions.
    let transfer_start = Instant::now();

    // receive file
    loop {
        tokio::task::yield_now().await;
        let chunk = receive_chunk(stream).await?;
        if chunk.len() == 0 {
            break;
        }
        // saturating: a peer that sends more data than the advertised file size must
        // not underflow (the loop is bounded by the end-of-file sentinel, not by this)
        bytes_left = bytes_left.saturating_sub(chunk.len() as u64);
        out_file.write_all(&chunk)?;
        let percent_done = ((file_size - bytes_left) as f64 / file_size as f64) * 100.0;
        ui.update_progress_bar(percent_done as u8);
        if last_details.elapsed() >= Duration::from_millis(250) {
            last_details = Instant::now();
            let done = file_size - bytes_left;
            let (total_pct, total_text) = totals.snapshot(done, file_size);
            ui.update_total_progress_bar(total_pct);
            let recent = rate.sample(done);
            let (data, clock) = utils::progress_details_parts_at(
                done,
                file_size,
                transfer_start.elapsed().as_secs_f64(),
                recent,
            );
            ui.update_progress_details(&format!("{}\n{}", data, clock), &total_text);
        }
    }

    // The file is whole: close it before the rename so every byte is with the kernel, then put it
    // under its real name. Done before the peer is told we finished, so "Transfer complete" can
    // never appear while a .part is still lying there.
    drop(out_file);
    fs::rename(&part_path, &full_path)?;

    // tell sending end we're finished
    stream.write_u64(1).await?;

    // stats
    ui.update_progress_bar(100);
    totals.bytes_done += file_size;
    let (total_pct, total_text) = totals.snapshot(0, 0);
    ui.update_total_progress_bar(total_pct);
    ui.update_progress_details(&utils::progress_details(file_size, file_size, transfer_start.elapsed().as_secs_f64()), &total_text);
    // Stat the finished file by path: the handle it used to stat was the .part one, which the
    // rename above has already closed and moved.
    let output_size = fs::metadata(&full_path)
        .expect("could not get output file metadata")
        .len();
    let dest_filename = full_path
        .file_name()
        .expect("output file didn't have a name")
        .to_string_lossy();
    ui.output(&format!(
        "Received file {}. Size: {}.",
        dest_filename,
        utils::make_size_readable(output_size)
    ));
    let finish = Instant::now();
    // Every figure below measures the data phase, not the whole call. The wait for a file-conflict
    // answer sits in between -- and it is a *human* wait, seconds or minutes while somebody types a
    // new filename (白い熊, 2026-08-11). Counting that as transfer time made the elapsed line climb
    // while nothing moved, and left size/elapsed disagreeing with the quoted speed.
    let elapsed = (finish - transfer_start).as_secs_f64();
    ui.output(&format!("Receiving took {}", utils::format_time(elapsed)));

    let megabits = 8.0 * (file_size as f64 / 1_000_000.0);
    let mbps = megabits / (finish - transfer_start).as_secs_f64();
    // MB/s first: it is the number that means something next to a file size, and the one both
    // ends of a transfer are actually comparing. Megabits stay in brackets because that is what
    // link rates are quoted in, so the two are only ever a factor of eight apart on the same line.
    let mbytes_per_sec = (file_size as f64 / 1_000_000.0) / (finish - transfer_start).as_secs_f64();
    ui.output(&format!("Speed: {:.2}MB/s ({:.2}mbps)", mbytes_per_sec, mbps));

    // wait for double confirmation
    if last_file {
        match timeout(Duration::from_secs(2), stream.read_u64()).await {
            Ok(res) => {
                res?;
            }
            Err(_e) => {
                ui.output("Didn't receive confirmation");
            }
        };
    } else {
        let _reply = stream.read_u64().await?;
    }

    Ok(())
}

// A transfer that has gone quiet for this long has a peer that is not coming back. Keepalive
// (see start_transfer) catches the same thing, but only after 10s idle plus three probes, and
// during those ~25 seconds the receiving window still looks live -- progress bar frozen, log
// stopped, Cancel the only way out (白い熊, 2026-08-11: cancelling on the phone left the desktop
// sitting there). This is the faster of the two, and it is safe to make it this short *here*
// because it guards only the chunk loop, where data arrives continuously by definition. The
// waits that legitimately take minutes -- the conflict hash of a multi-gigabyte file -- happen
// before it. The longest silence ever measured mid-transfer on these devices was 12 seconds,
// during the Wi-Fi power-save stalls the WifiLock has since removed, so this leaves 2.5x margin.
const CHUNK_IDLE_TIMEOUT: Duration = Duration::from_secs(30);

async fn receive_chunk<S: AsyncRead + Unpin>(stream: &mut S) -> Result<Vec<u8>, FCError> {
    // receive chunk size. 0 is the legitimate end-of-file sentinel; a larger-than-possible
    // value means a corrupt or hostile stream, and must be rejected before we allocate a
    // receive buffer of that size.
    let chunk_size = match timeout(CHUNK_IDLE_TIMEOUT, stream.read_u64()).await {
        Ok(size) => size?,
        Err(_) => {
            fc_error("The other device stopped sending. It was probably cancelled there.")?;
            unreachable!("fc_error always returns Err")
        }
    };
    if chunk_size == 0 {
        return Ok(vec![]);
    }
    if chunk_size > MAX_CHUNK_BYTES {
        fc_error(&format!(
            "Chunk size {} from peer is out of range",
            chunk_size
        ))?;
    }
    // receive chunk (raw bytes; the Noise transport already authenticated and decrypted it)
    let mut chunk = vec![0u8; chunk_size as usize];
    // The body gets the same deadline as the header. A peer that vanishes mid-chunk is exactly as
    // gone as one that vanishes between them, and read_exact would otherwise wait for ever for the
    // remainder of a chunk whose sender no longer exists.
    match timeout(CHUNK_IDLE_TIMEOUT, stream.read_exact(&mut chunk)).await {
        Ok(result) => {
            result?;
        }
        Err(_) => {
            fc_error("The other device stopped sending mid-file. It was probably cancelled there.")?;
        }
    }
    Ok(chunk)
}

pub(crate) fn sanitize_relative_filename(filename: &str) -> Result<PathBuf, FCError> {
    let mut sanitized = PathBuf::new();
    for component in Path::new(filename).components() {
        match component {
            Component::Normal(part) => sanitized.push(part),
            Component::CurDir => (),
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                fc_error(&format!("Received invalid filename path: {}", filename))?
            }
        }
    }
    if sanitized.as_os_str().is_empty() {
        fc_error("Received empty filename")?;
    }
    Ok(sanitized)
}

async fn receive_file_details<S: AsyncRead + Unpin>(
    stream: &mut S,
) -> Result<(String, u64), FCError> {
    // receive size of filename. real paths fit comfortably under the bound; an
    // unbounded value is a memory-exhaustion lever, so reject before allocating.
    let filename_size = stream.read_u64().await?;
    if filename_size > MAX_FILENAME_BYTES {
        fc_error(&format!(
            "Filename length {} from peer is out of range",
            filename_size
        ))?;
    }
    // receive filename
    let mut filename_bytes = vec![0; filename_size as usize];
    stream.read_exact(&mut filename_bytes).await?;
    let filename = String::from_utf8(filename_bytes)?;
    // receive file size
    let file_size = stream.read_u64().await?;
    Ok((filename, file_size))
}

// returns Ok(true) if we need to perform the transfer

/// What the sending device decided about a file we already have.
enum ConflictOutcome {
    Skip,
    Overwrite,
    /// Receive it, under the name we were given (Some) or the one already agreed (None).
    Receive(Option<String>),
}

/// The fork's file-conflict exchange, receiving half. The wire format is documented on the
/// sending side (sending.rs::resolve_conflict); this half only reports and obeys.
async fn resolve_conflict<S: AsyncRead + AsyncWrite + Unpin>(
    full_path: &Path,
    incoming_size: u64,
    stream: &mut S,
) -> Result<ConflictOutcome, FCError> {
    if !full_path.is_file() {
        stream.write_u64(0).await?;
        return Ok(ConflictOutcome::Receive(None));
    }
    let local_size = fs::metadata(full_path)?.len();
    let same_size = local_size == incoming_size;
    stream.write_u64(if same_size { 1 } else { 2 }).await?;
    if same_size {
        let local_hash = utils::hash_file(full_path)?;
        let mut peer_hash = vec![0; 32];
        stream.read_exact(&mut peer_hash).await?;
        let identical = local_hash[..] == peer_hash[..];
        stream.write_u64(if identical { 1 } else { 0 }).await?;
    }
    match stream.read_u64().await? {
        0 => Ok(ConflictOutcome::Skip),
        1 => Ok(ConflictOutcome::Overwrite),
        2 => {
            let name_len = stream.read_u64().await?;
            if name_len > MAX_FILENAME_BYTES {
                fc_error("Peer sent an unreasonably long replacement filename")?;
            }
            let mut buffer = vec![0; name_len as usize];
            stream.read_exact(&mut buffer).await?;
            let name = String::from_utf8(buffer)?;
            Ok(ConflictOutcome::Receive(Some(name)))
        }
        other => {
            fc_error(&format!("Peer sent an unknown file decision: {}", other))?;
            unreachable!()
        }
    }
}

async fn check_for_file<S: AsyncRead + AsyncWrite + Unpin>(
    filename: &Path,
    size: u64,
    stream: &mut S,
) -> Result<bool, FCError> {
    // check if file by this name and size exists
    if filename.is_file() {
        // check size
        let metadata = fs::metadata(filename)?;
        let local_size = metadata.len();
        if size == local_size {
            stream.write_u64(1).await?;
            let mut hashes_match = true;
            let local_hash = utils::hash_file(filename)?;
            let mut peer_hash = vec![0; 32];
            stream.read_exact(&mut peer_hash).await?;
            for i in 0..local_hash.len() {
                if local_hash[i] != peer_hash[i] {
                    hashes_match = false;
                }
            }
            stream.write_u64(if hashes_match { 1 } else { 0 }).await?;
            Ok(!hashes_match)
        } else {
            stream.write_u64(0).await?;
            // TODO: ugly hack to get around lifetime issue? sending end didn't receive this last reply when calculating hash of large file.
            sleep(time::Duration::from_secs(1)).await;
            Ok(true)
        }
    } else {
        stream.write_u64(0).await?;
        // TODO: ugly hack to get around lifetime issue? sending end didn't receive this last reply when calculating hash of large file.
        sleep(time::Duration::from_secs(1)).await;
        Ok(true)
    }
}
