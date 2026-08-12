use crate::{utils, FCError, CHUNKSIZE, UI};
use std::{
    fs::{metadata, File},
    io::Read,
    path::Path,
    time::{Duration, Instant},
};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

// v10+: file contents are protected by the Noise transport (see core/src/noise.rs), which
// wraps the whole connection, so chunks are sent as raw bytes here — no separate
// application-level encryption.
// `relative_name` is the path the peer stores the file under, relative to the folder they
// chose, always with "/" separators. It is resolved when the user makes their selection
// (utils::expand_selection), not here.
pub async fn send_file<S: AsyncRead + AsyncWrite + Unpin, T: UI>(
    file: &Path,
    relative_name: &str,
    stream: &mut S,
    totals: &mut crate::Totals,
    ui: &T,
    peer_is_fork: bool,
    conflict_rx: &mut tokio::sync::mpsc::Receiver<crate::FileConflictChoice>,
) -> Result<(), FCError> {
    let mut handle = File::open(file)?;
    let metadata = metadata(file)?;
    let size = metadata.len();
    let mut bytes_left = size;
    ui.output(&format!("File size: {}", utils::make_size_readable(size)));

    // send file details
    send_file_details(relative_name, size, stream).await?;

    // check to see if receiving end already has the file
    if peer_is_fork {
        // Fork-only (see FileConflictChoice): the peer says whether it has a file by this name,
        // and *this* side decides what happens to it, because this is the side with the user who
        // chose the files. Upstream skips an identical file silently and renames a differing one
        // without asking, which is a decision made on the wrong device.
        match resolve_conflict(&file, relative_name, size, stream, ui, conflict_rx).await? {
            Some(name) => {
                if name != relative_name {
                    ui.output(&format!("Sending it as \"{}\".", name));
                }
            }
            None => {
                ui.output("Skipping this file: the other device already has it.");
                return Ok(());
            }
        }
    } else {
        let need_transfer = check_for_file(&file, stream).await?;
        if !need_transfer {
            ui.output("Recipient already has this file, skipping.");
            return Ok(());
        }
    }

    // show progress bar
    ui.show_progress_bar();
    let (total_pct, total_text) = totals.snapshot(0, size);
    ui.update_total_progress_bar(total_pct);
    ui.update_progress_details(&utils::progress_details(0, size, 0.0), &total_text);
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

    let mut buffer = vec![0u8; CHUNKSIZE];

    while bytes_left > 0 {
        tokio::task::yield_now().await;
        match handle.read(&mut buffer) {
            Ok(bytes_read) if bytes_read == 0 => {
                // EOF, shouldn't hit this due to while loop condition
                ui.output("Hit EOF");
                break;
            }
            Ok(bytes_read) => {
                bytes_left -= bytes_read as u64;
                send_chunk(&buffer[..bytes_read], stream).await?;
                let percent_done = ((size - bytes_left) as f64 / size as f64) * 100.;
                ui.update_progress_bar(percent_done as u8);
                // Throttled: at 1 MB a chunk a fast link would otherwise redraw this dozens of
                // times a second, and it is a line of text a human is reading.
                if last_details.elapsed() >= Duration::from_millis(250) {
                    last_details = Instant::now();
                    let done = size - bytes_left;
                    let (total_pct, total_text) = totals.snapshot(done, size);
                    ui.update_total_progress_bar(total_pct);
                    let recent = rate.sample(done);
                    let (data, clock) = utils::progress_details_parts_at(
                        done,
                        size,
                        transfer_start.elapsed().as_secs_f64(),
                        recent,
                    );
                    ui.update_progress_details(&format!("{}\n{}", data, clock), &total_text);
                }
            }
            Err(e) => Err(e)?,
        }
    }

    // send chunkSize of 0
    stream.write_u64(0).await?;

    // stats
    ui.update_progress_bar(100);
    totals.bytes_done += size;
    let (total_pct, total_text) = totals.snapshot(0, 0);
    ui.update_total_progress_bar(total_pct);
    ui.update_progress_details(&utils::progress_details(size, size, transfer_start.elapsed().as_secs_f64()), &total_text);
    let finish = Instant::now();
    // Every figure below measures the data phase, not the whole call. The wait for a file-conflict
    // answer sits in between -- and it is a *human* wait, seconds or minutes while somebody types a
    // new filename (白い熊, 2026-08-11). Counting that as transfer time made the elapsed line climb
    // while nothing moved, and left size/elapsed disagreeing with the quoted speed.
    let elapsed = (finish - transfer_start).as_secs_f64();
    ui.output(&format!("Sending took {}", utils::format_time(elapsed)));

    let megabits = 8.0 * (size as f64 / 1_000_000.0);
    let mbps = megabits / (finish - transfer_start).as_secs_f64();
    // MB/s first; see the matching line in receiving.rs.
    let mbytes_per_sec = (size as f64 / 1_000_000.0) / (finish - transfer_start).as_secs_f64();
    ui.output(&format!("Speed: {:.2}MB/s ({:.2}mbps)", mbytes_per_sec, mbps));

    // listen for receiving end to tell us they have everything
    stream.read_u64().await?;

    // send double confirmation
    // std::thread::sleep(std::time::Duration::from_secs(5));
    stream.write_u64(1).await?;

    Ok(())
}

async fn send_chunk<S: AsyncWrite + Unpin>(chunk: &[u8], stream: &mut S) -> Result<(), FCError> {
    // length-prefixed raw bytes; confidentiality/integrity come from the Noise transport
    stream.write_u64(chunk.len() as u64).await?;
    stream.write_all(chunk).await?;
    Ok(())
}

async fn send_file_details<S: AsyncWrite + Unpin>(
    filename: &str,
    size: u64,
    stream: &mut S,
) -> std::io::Result<()> {
    // send size of filename
    stream.write_u64(filename.len() as u64).await?;
    // send filename
    stream.write_all(filename.as_bytes()).await?;
    // send file size
    stream.write_u64(size).await?;
    Ok(())
}

// returns Ok(true) if we need to perform the transfer

/// The fork's file-conflict exchange, sending half.
///
/// Wire (only ever spoken when both ends announced a fork version):
///   receiver -> u64 status   0 = no such file, 1 = same name and size, 2 = same name, other size
///   if status != 0:
///     when status == 1: sender -> 32-byte hash of its copy, receiver -> u64 1 if identical
///     sender -> u64 choice   0 = skip, 1 = overwrite, 2 = rename
///     when choice == 2: sender -> u64 length + that many bytes of the new relative name
///
/// Returns the name to send the file under, or None to skip it.
async fn resolve_conflict<S: AsyncRead + AsyncWrite + Unpin, T: UI>(
    file: &Path,
    relative_name: &str,
    size: u64,
    stream: &mut S,
    ui: &T,
    conflict_rx: &mut tokio::sync::mpsc::Receiver<crate::FileConflictChoice>,
) -> Result<Option<String>, FCError> {
    let status = stream.read_u64().await?;
    if status == 0 {
        return Ok(Some(relative_name.to_string()));
    }

    // Same size: settle whether the bytes match too, so the question we ask is the right one.
    let identical = if status == 1 {
        let hash = utils::hash_file(file)?;
        stream.write_all(&hash).await?;
        stream.read_u64().await? == 1
    } else {
        false
    };

    ui.output(&format!(
        "The other device already has \"{}\"{}.",
        relative_name,
        if identical { " (identical)" } else { " (a different file)" }
    ));
    // Drop any answer left over from a question the user was too slow to answer.
    while conflict_rx.try_recv().is_ok() {}
    ui.ask_file_conflict(relative_name, size, size, identical);
    let choice = match conflict_rx.recv().await {
        Some(choice) => choice,
        // The channel is gone: the transfer was cancelled while the dialog was up.
        None => crate::FileConflictChoice::Skip,
    };

    match choice {
        crate::FileConflictChoice::Skip => {
            stream.write_u64(0).await?;
            Ok(None)
        }
        crate::FileConflictChoice::Overwrite => {
            stream.write_u64(1).await?;
            Ok(Some(relative_name.to_string()))
        }
        crate::FileConflictChoice::Rename(new_name) => {
            let name = if new_name.trim().is_empty() {
                relative_name.to_string()
            } else {
                new_name
            };
            stream.write_u64(2).await?;
            let bytes = name.as_bytes();
            stream.write_u64(bytes.len() as u64).await?;
            stream.write_all(bytes).await?;
            Ok(Some(name))
        }
    }
}

async fn check_for_file<S: AsyncRead + AsyncWrite + Unpin>(
    filename: &Path,
    stream: &mut S,
) -> Result<bool, FCError> {
    let has_file = stream.read_u64().await?;
    if has_file == 1 {
        let hash = utils::hash_file(filename)?;
        // write_all, not write: a short write would send a truncated hash and leave the
        // rest of it to be read as the next protocol field
        stream.write_all(&hash).await?;
        let hashes_match = stream.read_u64().await?;
        Ok(hashes_match != 1) // if hashes match, return false because we don't need transfer
    } else {
        Ok(true)
    }
}

/*
mod tests {
    use tokio::io::AsyncReadExt;

    // nc -l 4387
    // test that timeout closes tcp connection early
    #[tokio::test]
    async fn timeout() {
        let addr = "127.0.0.1:4387".parse::<std::net::SocketAddr>().unwrap();
        println!("waiting...");
        let mut stream = tokio::net::TcpStream::connect(addr).await.unwrap();
        let data = tokio::time::timeout(std::time::Duration::from_secs(5), stream.read_u64()).await;
        println!("{:?}", data);
        println!("timed out after 5 seconds");
    }
}
*/
