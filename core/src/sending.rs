use crate::{utils, FCError, CHUNKSIZE, UI};
use aes_gcm::{aead::Aead, AeadCore, Aes256Gcm, KeyInit};
use std::{
    fs::{metadata, File},
    io::Read,
    path::Path,
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
};

pub async fn send_file<T: UI>(
    file: &Path,
    prefix: &Path,
    key: &[u8],
    stream: &mut TcpStream,
    totals: &mut crate::Totals,
    ui: &T,
) -> Result<(), FCError> {
    let start = Instant::now();
    let cipher = Aes256Gcm::new_from_slice(key).expect("Invalid AES-256-GCM key length");
    let mut handle = File::open(file)?;
    let metadata = metadata(file)?;
    let size = metadata.len();
    let mut bytes_left = size;
    ui.output(&format!("File size: {}", utils::make_size_readable(size)));

    // send file details
    let mut filename = file.strip_prefix(prefix)?.to_string_lossy().to_string();
    if cfg!(windows) {
        filename = filename.replace("\\", "/");
    }
    send_file_details(&filename, size, stream).await?;

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
                encrypt_and_send_chunk(&buffer[..bytes_read], &cipher, stream).await?;
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

async fn encrypt_and_send_chunk(
    chunk: &[u8],
    cipher: &Aes256Gcm,
    stream: &mut TcpStream,
) -> Result<(), FCError> {
    // generate nonce
    let nonce = aes_gcm::Aes256Gcm::generate_nonce(rand::thread_rng());

    // encrypt
    let mut encrypted_chunk = cipher.encrypt(&nonce, chunk)?;

    let mut nonce_and_chunk = nonce.to_vec();
    nonce_and_chunk.append(&mut encrypted_chunk);

    // send size
    stream.write_u64(nonce_and_chunk.len() as u64).await?;

    // write chunk
    stream.write_all(&nonce_and_chunk).await?;

    Ok(())
}

async fn send_file_details(
    filename: &str,
    size: u64,
    stream: &mut TcpStream,
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
async fn check_for_file(filename: &Path, stream: &mut TcpStream) -> Result<bool, FCError> {
    let has_file = stream.read_u64().await?;
    if has_file == 1 {
        let hash = utils::hash_file(filename)?;
        stream.write(&hash).await?;
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
