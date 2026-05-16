use async_trait::async_trait;
use bytes::BytesMut;
use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use util::{Conn, Error, Result};

const STUN_HEADER_SIZE: usize = 20;
const CHANNEL_DATA_HEADER_SIZE: usize = 4;
const CHANNEL_DATA_PADDING: usize = 4;
const TCP_READ_CHUNK: usize = 8192;
const MAX_TURN_FRAME_SIZE: usize = u16::MAX as usize + STUN_HEADER_SIZE;
const STUN_MAGIC_COOKIE: [u8; 4] = [0x21, 0x12, 0xA4, 0x42];
const CHANNEL_NUMBER_MIN: u16 = 0x4000;
const CHANNEL_NUMBER_MAX: u16 = 0x7FFF;

pub struct TcpTurnConn {
    reader: Mutex<OwnedReadHalf>,
    writer: Mutex<OwnedWriteHalf>,
    pending: Mutex<BytesMut>,
    local_addr: SocketAddr,
    remote_addr: SocketAddr,
    closed: AtomicBool,
}

impl TcpTurnConn {
    pub async fn connect(remote_addr: SocketAddr) -> Result<Self> {
        let stream = TcpStream::connect(remote_addr).await?;
        stream.set_nodelay(true)?;
        let local_addr = stream.local_addr()?;
        let remote_addr = stream.peer_addr()?;
        let (reader, writer) = stream.into_split();

        Ok(Self {
            reader: Mutex::new(reader),
            writer: Mutex::new(writer),
            pending: Mutex::new(BytesMut::with_capacity(TCP_READ_CHUNK)),
            local_addr,
            remote_addr,
            closed: AtomicBool::new(false),
        })
    }

    async fn read_more(&self) -> Result<()> {
        let (n, pending_len) = {
            let mut reader = self.reader.lock().await;
            let mut pending = self.pending.lock().await;
            pending.reserve(TCP_READ_CHUNK);
            let n = reader.read_buf(&mut *pending).await?;
            (n, pending.len())
        };

        if n == 0 {
            self.closed.store(true, Ordering::Relaxed);
            return Err(
                io::Error::new(io::ErrorKind::UnexpectedEof, "TURN TCP stream closed").into(),
            );
        }

        if pending_len > MAX_TURN_FRAME_SIZE * 4 {
            self.closed.store(true, Ordering::Relaxed);
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "TURN TCP frame buffer overflow",
            )
            .into());
        }
        Ok(())
    }
}

#[async_trait]
impl Conn for TcpTurnConn {
    async fn connect(&self, addr: SocketAddr) -> Result<()> {
        if addr == self.remote_addr {
            Ok(())
        } else {
            Err(Error::Other(format!(
                "TURN TCP conn is already connected to {}, not {}",
                self.remote_addr, addr
            )))
        }
    }

    async fn recv(&self, buf: &mut [u8]) -> Result<usize> {
        let (n, _) = self.recv_from(buf).await?;
        Ok(n)
    }

    async fn recv_from(&self, buf: &mut [u8]) -> Result<(usize, SocketAddr)> {
        loop {
            {
                let mut pending = self.pending.lock().await;
                match next_turn_frame_len(pending.as_ref())? {
                    Some(frame_len) => {
                        if buf.len() < frame_len {
                            return Err(io::Error::new(
                                io::ErrorKind::InvalidInput,
                                format!("short read buffer: {} < {}", buf.len(), frame_len),
                            )
                            .into());
                        }

                        let frame = pending.split_to(frame_len);
                        buf[..frame_len].copy_from_slice(frame.as_ref());
                        return Ok((frame_len, self.remote_addr));
                    }
                    None => {}
                }
            }

            if self.closed.load(Ordering::Relaxed) {
                return Err(Error::ErrUseClosedNetworkConn);
            }
            self.read_more().await?;
        }
    }

    async fn send(&self, buf: &[u8]) -> Result<usize> {
        self.send_to(buf, self.remote_addr).await
    }

    async fn send_to(&self, buf: &[u8], _target: SocketAddr) -> Result<usize> {
        if self.closed.load(Ordering::Relaxed) {
            return Err(Error::ErrUseClosedNetworkConn);
        }

        let mut writer = self.writer.lock().await;
        writer.write_all(buf).await?;
        Ok(buf.len())
    }

    fn local_addr(&self) -> Result<SocketAddr> {
        Ok(self.local_addr)
    }

    fn remote_addr(&self) -> Option<SocketAddr> {
        Some(self.remote_addr)
    }

    async fn close(&self) -> Result<()> {
        self.closed.store(true, Ordering::Relaxed);
        let mut writer = self.writer.lock().await;
        let _ = writer.shutdown().await;
        Ok(())
    }

    fn as_any(&self) -> &(dyn std::any::Any + Send + Sync) {
        self
    }
}

fn next_turn_frame_len(buf: &[u8]) -> Result<Option<usize>> {
    if buf.len() < CHANNEL_DATA_HEADER_SIZE {
        return Ok(None);
    }

    let first_word = u16::from_be_bytes([buf[0], buf[1]]);
    if is_channel_number(first_word) {
        let payload_len = u16::from_be_bytes([buf[2], buf[3]]) as usize;
        let padded_payload_len = round_up_to_padding(payload_len, CHANNEL_DATA_PADDING);
        let frame_len = CHANNEL_DATA_HEADER_SIZE + padded_payload_len;
        validate_frame_len(frame_len)?;
        return Ok((buf.len() >= frame_len).then_some(frame_len));
    }

    if buf.len() < STUN_HEADER_SIZE {
        return Ok(None);
    }

    if is_stun_message(buf) {
        let payload_len = u16::from_be_bytes([buf[2], buf[3]]) as usize;
        let frame_len = STUN_HEADER_SIZE + payload_len;
        validate_frame_len(frame_len)?;
        return Ok((buf.len() >= frame_len).then_some(frame_len));
    }

    Err(io::Error::new(io::ErrorKind::InvalidData, "invalid TURN TCP frame").into())
}

fn is_stun_message(buf: &[u8]) -> bool {
    buf.len() >= STUN_HEADER_SIZE && (buf[0] & 0b1100_0000) == 0 && buf[4..8] == STUN_MAGIC_COOKIE
}

fn is_channel_number(value: u16) -> bool {
    (CHANNEL_NUMBER_MIN..=CHANNEL_NUMBER_MAX).contains(&value)
}

fn round_up_to_padding(value: usize, padding: usize) -> usize {
    if value == 0 {
        0
    } else {
        value.div_ceil(padding) * padding
    }
}

fn validate_frame_len(frame_len: usize) -> Result<()> {
    if frame_len > MAX_TURN_FRAME_SIZE {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "TURN TCP frame too large").into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_complete_stun_frame() {
        let mut frame = vec![0u8; STUN_HEADER_SIZE + 12];
        frame[2..4].copy_from_slice(&12u16.to_be_bytes());
        frame[4..8].copy_from_slice(&STUN_MAGIC_COOKIE);

        assert_eq!(next_turn_frame_len(&frame).unwrap(), Some(32));
    }

    #[test]
    fn waits_for_incomplete_stun_frame() {
        let mut frame = vec![0u8; STUN_HEADER_SIZE + 8];
        frame[2..4].copy_from_slice(&12u16.to_be_bytes());
        frame[4..8].copy_from_slice(&STUN_MAGIC_COOKIE);

        assert_eq!(next_turn_frame_len(&frame).unwrap(), None);
    }

    #[test]
    fn parses_padded_channel_data_frame() {
        let mut frame = vec![0u8; CHANNEL_DATA_HEADER_SIZE + 8];
        frame[0..2].copy_from_slice(&CHANNEL_NUMBER_MIN.to_be_bytes());
        frame[2..4].copy_from_slice(&5u16.to_be_bytes());

        assert_eq!(next_turn_frame_len(&frame).unwrap(), Some(12));
    }

    #[test]
    fn waits_for_incomplete_channel_data_frame() {
        let mut frame = vec![0u8; CHANNEL_DATA_HEADER_SIZE + 4];
        frame[0..2].copy_from_slice(&CHANNEL_NUMBER_MIN.to_be_bytes());
        frame[2..4].copy_from_slice(&5u16.to_be_bytes());

        assert_eq!(next_turn_frame_len(&frame).unwrap(), None);
    }

    #[test]
    fn back_to_back_complete_frames_report_first_frame_len() {
        let mut first = vec![0u8; STUN_HEADER_SIZE + 8];
        first[2..4].copy_from_slice(&8u16.to_be_bytes());
        first[4..8].copy_from_slice(&STUN_MAGIC_COOKIE);

        let mut second = vec![0u8; CHANNEL_DATA_HEADER_SIZE + 4];
        second[0..2].copy_from_slice(&CHANNEL_NUMBER_MIN.to_be_bytes());
        second[2..4].copy_from_slice(&4u16.to_be_bytes());

        let mut frames = first.clone();
        frames.extend_from_slice(&second);

        assert_eq!(next_turn_frame_len(&frames).unwrap(), Some(first.len()));
    }

    #[test]
    fn waits_for_short_channel_data_header() {
        let header = CHANNEL_NUMBER_MIN.to_be_bytes();

        assert_eq!(next_turn_frame_len(&[]).unwrap(), None);
        assert_eq!(next_turn_frame_len(&header[..1]).unwrap(), None);
        assert_eq!(next_turn_frame_len(&header).unwrap(), None);
    }

    #[test]
    fn rejects_frame_len_larger_than_max_turn_frame_size() {
        assert!(validate_frame_len(MAX_TURN_FRAME_SIZE + 1).is_err());
    }

    #[test]
    fn rejects_invalid_full_header() {
        let frame = vec![0x20u8; STUN_HEADER_SIZE];

        assert!(next_turn_frame_len(&frame).is_err());
    }
}
