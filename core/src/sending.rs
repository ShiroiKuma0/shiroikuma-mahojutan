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
) -> Result<(), FCError> {
    let start = Instant::now();
    let mut handle = File::open(file)?;
    let metadata = metadata(file)?;
    let size = metadata.len();
    let mut bytes_left = size;
    ui.output(&format!("File size: {}", utils::make_size_readable(size)));

    // send file details
    send_file_details(relative_name, size, stream).await?;

    // check to see if receiving end already has the file
    let need_transfer = check_for_file(&file, stream).await?;
    if !need_transfer {
        ui.output("Recipient already has this file, skipping.");
        return Ok(());
    }

    // show progress bar
    ui.show_progress_bar();
    let (total_pct, total_text) = totals.snapshot(0, size);
    ui.update_total_progress_bar(total_pct);
    ui.update_progress_details(&utils::progress_details(0, size, 0.0), &total_text);
    let mut last_details = Instant::now();

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
                    ui.update_progress_details(
                        &utils::progress_details(done, size, start.elapsed().as_secs_f64()),
                        &total_text,
                    );
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
    ui.update_progress_details(&utils::progress_details(size, size, start.elapsed().as_secs_f64()), &total_text);
    let finish = Instant::now();
    let elapsed = (finish - start).as_secs_f64();
    ui.output(&format!("Sending took {}", utils::format_time(elapsed)));

    let megabits = 8.0 * (size as f64 / 1_000_000.0);
    let mbps = megabits / elapsed;
    ui.output(&format!("Speed: {:.2}mbps", mbps));

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
