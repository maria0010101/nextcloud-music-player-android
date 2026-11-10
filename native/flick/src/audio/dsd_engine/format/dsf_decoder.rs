use super::{DsdBitOrder, DsdChannelConfig, DsdChannelLayout, DsdFormatDecoder};
use anyhow::{anyhow, bail, Context, Result};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

const DSF_BLOCK_CACHE_LIMIT: usize = 64 * 1024;

pub struct DsfDecoder {
    file: File,
    sample_rate: u32,
    channels: u16,
    total_samples: u64,
    data_offset: u64,
    block_size: u32,
    block: Vec<u8>,
    channel_buf: Vec<u8>,
    loaded_block: Option<u64>,
    current_position: u64,
    bit_order: DsdBitOrder,
    channel_config: Option<DsdChannelConfig>,
    finished: bool,
}

impl DsfDecoder {
    pub fn open(path: &Path) -> Result<Self> {
        let mut file = File::open(path)?;
        // Android document providers do not always expose a useful stat length.
        // DSF requires seeking anyway; use the actual seekable extent.
        let actual_size = file.seek(SeekFrom::End(0))
            .context("Could not determine the seekable DSF file length")?;
        file.seek(SeekFrom::Start(0))?;
        let mut header = [0u8; 28];
        file.read_exact(&mut header).context("Truncated DSF DSD header (expected 28 bytes)")?;
        let u32_at = |bytes: &[u8], offset| u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap());
        let u64_at = |bytes: &[u8], offset| u64::from_le_bytes(bytes[offset..offset + 8].try_into().unwrap());
        if &header[..4] != b"DSD " || u64_at(&header, 4) != 28 {
            bail!("Invalid DSF DSD chunk");
        }
        let file_size = u64_at(&header, 12);
        let metadata_offset = u64_at(&header, 20);
        if actual_size < 92 {
            bail!("Truncated DSF container: actual={actual_size}, declared={file_size}, metadata={metadata_offset}, minimum=92");
        }
        let mut fmt = None;
        let mut data = None;
        let mut offset = 28u64;
        // Tag edits can leave the outer size or ID3 pointer stale. Validate the
        // entire audio chunk against the physical file, never an inferred tail.
        while offset.checked_add(12).is_some_and(|end| end <= actual_size) {
            file.seek(SeekFrom::Start(offset))?;
            let mut chunk = [0u8; 12];
            file.read_exact(&mut chunk)?;
            let size = u64_at(&chunk, 4);
            let end = offset.checked_add(size).ok_or_else(|| anyhow!("DSF chunk size overflow"))?;
            if size < 12 || end > actual_size {
                bail!("Invalid or truncated DSF chunk: offset={offset}, size={size}, end={end}, actual={actual_size}, declared={file_size}, metadata={metadata_offset}");
            }
            match &chunk[..4] {
                b"fmt " => {
                    if fmt.is_some() || size < 52 {
                        bail!("Invalid DSF format chunk");
                    }
                    let mut fields = [0u8; 40];
                    file.read_exact(&mut fields)?;
                    if u32_at(&fields, 0) != 1 || u32_at(&fields, 4) != 0 {
                        bail!("Unsupported DSF format version or encoding");
                    }
                    fmt = Some(fields);
                }
                b"data" => { data = Some((offset + 12, size - 12)); break; }
                _ => {}
            }
            offset = end;
        }
        let fmt = fmt.ok_or_else(|| anyhow!("DSF format chunk missing"))?;
        let (data_offset, data_size) = data.ok_or_else(|| anyhow!("DSF data chunk missing"))?;
        let data_end = data_offset.checked_add(data_size)
            .ok_or_else(|| anyhow!("DSF data boundary overflow"))?;
        if metadata_offset != 0 && metadata_offset < data_end {
            bail!("DSF metadata overlaps the audio chunks: metadata={metadata_offset}, data_start={data_offset}, data_end={data_end}, actual={actual_size}, declared={file_size}");
        }
        let channel_count = u32_at(&fmt, 12);
        let channel_type = u32_at(&fmt, 8);
        let sample_rate = u32_at(&fmt, 16);
        let bits_per_sample = u32_at(&fmt, 20);
        let total_samples = u64_at(&fmt, 24);
        let block_size = u32_at(&fmt, 32);
        if channel_count == 0 || channel_count > 32 || sample_rate == 0 || block_size == 0
            || block_size as u64 * channel_count as u64 > 8 * 1024 * 1024 {
            bail!("Invalid DSF channel/rate/block contract");
        }
        if total_samples % 8 != 0 {
            bail!("DSF bit-tail is not byte-aligned; refusing to truncate source samples");
        }
        let channels = channel_count as u16;
        // The channel_type field disambiguates layouts that share a count
        // (Quad vs FL/FR/C/LFE). A mismatch between type and count means the
        // container metadata is inconsistent; trust the count for byte
        // layout but still fold down with a defined arrangement.
        let channel_config = match DsdChannelConfig::from_channel_type(channel_type) {
            Some(config) if config.channels() == channels => Some(config),
            Some(config) => {
                log::warn!(
                    "[DSF] channel_type={} ({}) disagrees with channel_num={}; using count-based layout",
                    channel_type,
                    config.label(),
                    channels
                );
                DsdChannelConfig::from_count(channels)
            }
            None => {
                if channel_type != 0 {
                    log::warn!(
                        "[DSF] Unknown channel_type={}; using count-based layout",
                        channel_type
                    );
                }
                DsdChannelConfig::from_count(channels)
            }
        };
        let bytes_per_channel = total_samples / 8;
        let blocks = bytes_per_channel / block_size as u64
            + u64::from(bytes_per_channel % block_size as u64 != 0);
        let required = blocks.checked_mul(block_size as u64 * channels as u64)
            .ok_or_else(|| anyhow!("DSF sample count overflow"))?;
        if required > data_size {
            bail!("DSF data chunk is shorter than its declared channel blocks: required={required}, available={data_size}, samples={total_samples}, channels={channels}, block_size={block_size}");
        }
        // DSF's `bits per sample` field is really a bit-order flag:
        // 1 = LSB-first (the common case), 8 = MSB-first.
        let bit_order = match bits_per_sample {
            1 => DsdBitOrder::LsbFirst,
            8 => DsdBitOrder::MsbFirst,
            _ => bail!("Invalid DSF bit-order flag: {bits_per_sample}"),
        };
        let block_bytes = block_size as usize * channels as usize;
        let block_seconds = block_size as f64 / (sample_rate as f64 / 8.0);
        log::info!(
            "[DSF] opened: rate={} Hz channels={} bit_order={} samples={} duration={:.3}s block_size={}B block_seconds={:.3} data_size={}B",
            sample_rate,
            channels,
            bits_per_sample,
            total_samples,
            total_samples as f64 / sample_rate as f64,
            block_size,
            block_seconds,
            data_size
        );
        if block_seconds > 1.0 {
            log::warn!(
                "[DSF] Large block size: {} bytes/channel ({:.3}s); using bounded windowed reads",
                block_size,
                block_seconds
            );
        }
        let block = if block_bytes <= DSF_BLOCK_CACHE_LIMIT {
            vec![0u8; block_bytes]
        } else {
            Vec::new()
        };
        if file_size != actual_size || metadata_offset > actual_size {
            log::warn!(
                "[DSF] Stale outer size/tag pointer; complete audio chunk validated: declared={}, actual={}, metadata={}, data_end={}",
                file_size, actual_size, metadata_offset, data_end
            );
        }
        file.seek(SeekFrom::Start(data_offset))?;

        Ok(Self {
            file,
            sample_rate,
            channels,
            total_samples,
            data_offset,
            block_size,
            block,
            channel_buf: Vec::new(),
            loaded_block: None,
            current_position: 0,
            bit_order,
            channel_config,
            finished: total_samples == 0,
        })
    }

    pub fn block_size_per_channel(&self) -> u32 {
        self.block_size
    }
}

impl DsdFormatDecoder for DsfDecoder {
    fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    fn channels(&self) -> u16 {
        self.channels
    }

    fn total_samples(&self) -> u64 {
        self.total_samples
    }

    fn duration_secs(&self) -> f64 {
        if self.sample_rate == 0 {
            return 0.0;
        }
        self.total_samples as f64 / self.sample_rate as f64
    }

    fn seek(&mut self, sample: u64) -> Result<()> {
        // Wire bytes are the smallest lossless seek unit (eight DSD samples).
        self.current_position = sample.min(self.total_samples) / 8 * 8;
        self.loaded_block = None;
        self.finished = self.current_position >= self.total_samples;
        Ok(())
    }

    fn read_dsd_bytes(&mut self, buf: &mut [u8]) -> Result<usize> {
        if self.finished {
            return Ok(0);
        }

        let channels = self.channels as usize;
        if buf.len() < channels {
            bail!("DSF output buffer cannot hold a complete channel frame");
        }
        let frames = ((buf.len() / channels) as u64)
            .min((self.total_samples - self.current_position) / 8);
        let block_bytes = self.block_size as usize * channels;
        let mut written = 0;
        while written < frames as usize {
            let byte_position = self.current_position / 8;
            let block_index = byte_position / self.block_size as u64;
            let within = (byte_position % self.block_size as u64) as usize;
            let count = (frames as usize - written).min(self.block_size as usize - within);
            if block_bytes <= DSF_BLOCK_CACHE_LIMIT {
                if self.loaded_block != Some(block_index) {
                    self.file.seek(SeekFrom::Start(
                        self.data_offset + block_index * block_bytes as u64,
                    ))?;
                    // read_exact retries Interrupted/short FUSE reads, but rejects a
                    // genuinely truncated block instead of leaving channels skewed.
                    self.file.read_exact(&mut self.block)?;
                    self.loaded_block = Some(block_index);
                }
                for frame in 0..count {
                    for channel in 0..channels {
                        buf[(written + frame) * channels + channel] =
                            self.block[channel * self.block_size as usize + within + frame];
                    }
                }
            } else {
                // Nonstandard DSF files can declare multi-second blocks. Reading
                // a whole block here would issue multi-megabyte FUSE reads and can
                // stall the decoder. Read only the requested window per channel.
                let block_base = self.data_offset + block_index * block_bytes as u64;
                for channel in 0..channels {
                    let channel_offset = block_base
                        + (channel * self.block_size as usize + within) as u64;
                    self.file.seek(SeekFrom::Start(channel_offset))?;
                    self.channel_buf.resize(count, 0);
                    self.file.read_exact(&mut self.channel_buf)?;
                    for frame in 0..count {
                        buf[(written + frame) * channels + channel] = self.channel_buf[frame];
                    }
                }
            }
            written += count;
            self.current_position += count as u64 * 8;
        }
        self.finished = self.current_position >= self.total_samples;
        Ok(written * channels)
    }

    fn is_finished(&self) -> bool {
        self.finished
    }

    fn channel_layout(&self) -> DsdChannelLayout {
        // De-block here: the final padded macroblock and intra-block seeks
        // cannot be represented safely by a fixed SequentialBlocks layout.
        DsdChannelLayout::Interleaved
    }

    fn bit_order(&self) -> DsdBitOrder {
        self.bit_order
    }

    fn channel_config(&self) -> Option<DsdChannelConfig> {
        self.channel_config
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn put_u32(buf: &mut [u8], off: usize, value: u32) {
        buf[off..off + 4].copy_from_slice(&value.to_le_bytes());
    }

    fn put_u64(buf: &mut [u8], off: usize, value: u64) {
        buf[off..off + 8].copy_from_slice(&value.to_le_bytes());
    }

    fn synthetic_dsf_with_block_size(
        bits_per_sample: u32,
        audio: &[u8],
        block_size: u32,
    ) -> Vec<u8> {
        let mut buf = vec![0u8; 92];
        buf[0..4].copy_from_slice(b"DSD ");
        put_u64(&mut buf, 4, 28);
        put_u64(&mut buf, 12, 92 + audio.len() as u64);
        put_u64(&mut buf, 20, 0);
        buf[28..32].copy_from_slice(b"fmt ");
        put_u64(&mut buf, 32, 52);
        put_u32(&mut buf, 40, 1);
        put_u32(&mut buf, 44, 0);
        put_u32(&mut buf, 48, 2);
        put_u32(&mut buf, 52, 2);
        put_u32(&mut buf, 56, 2_822_400);
        put_u32(&mut buf, 60, bits_per_sample);
        put_u64(&mut buf, 64, ((audio.len() / 2) * 8) as u64);
        put_u32(&mut buf, 72, block_size);
        put_u32(&mut buf, 76, 0);
        buf[80..84].copy_from_slice(b"data");
        put_u64(&mut buf, 84, 12 + audio.len() as u64);
        buf.extend_from_slice(audio);
        buf
    }

    fn synthetic_dsf(bits_per_sample: u32, audio: &[u8]) -> Vec<u8> {
        synthetic_dsf_with_block_size(bits_per_sample, audio, 4096)
    }

    fn write_temp(name: &str, bytes: &[u8]) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!("flick_dsf_{}_{}.dsf", name, std::process::id()));
        std::fs::File::create(&path)
            .unwrap()
            .write_all(bytes)
            .unwrap();
        path
    }

    #[test]
    fn read_starts_at_spec_data_offset() {
        let audio: Vec<u8> = (0..8192).map(|i| (i * 7 + 3) as u8).collect();
        let path = write_temp("offset", &synthetic_dsf(1, &audio));

        let mut decoder = DsfDecoder::open(&path).unwrap();
        let mut out = vec![0u8; 8192];
        let read = decoder.read_dsd_bytes(&mut out).unwrap();
        let _ = std::fs::remove_file(&path);

        assert_eq!(read, 8192);
        let expected: Vec<_> = (0..4096).flat_map(|i| [audio[i], audio[4096 + i]]).collect();
        assert_eq!(out, expected, "DSF audio must be de-blocked from byte 92");
    }

    #[test]
    fn large_block_size_reads_are_windowed_and_deinterleaved() {
        let block_size = 128 * 1024;
        let blocks = 3;
        let mut audio = vec![0u8; blocks * 2 * block_size];
        for block in 0..blocks {
            let base = block * 2 * block_size;
            audio[base..base + block_size].fill(0x10 + block as u8);
            audio[base + block_size..base + 2 * block_size].fill(0x80 + block as u8);
        }
        let path = write_temp(
            "large_block",
            &synthetic_dsf_with_block_size(1, &audio, block_size as u32),
        );

        let mut decoder = DsfDecoder::open(&path).unwrap();
        let mut out = vec![0u8; audio.len()];
        let read = decoder.read_dsd_bytes(&mut out).unwrap();
        assert_eq!(read, audio.len());
        assert!(decoder.is_finished());

        for frame in 0..blocks * block_size {
            let block = frame / block_size;
            assert_eq!(out[frame * 2], 0x10 + block as u8);
            assert_eq!(out[frame * 2 + 1], 0x80 + block as u8);
        }

        decoder.seek((block_size as u64 + 8) * 8).unwrap();
        let mut tail = vec![0u8; 64 * 2];
        assert_eq!(decoder.read_dsd_bytes(&mut tail).unwrap(), tail.len());
        for frame in 0..64 {
            assert_eq!(tail[frame * 2], 0x11);
            assert_eq!(tail[frame * 2 + 1], 0x81);
        }

        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn bit_order_follows_header_flag() {
        let audio = vec![0u8; 8192];
        let lsb_path = write_temp("lsb", &synthetic_dsf(1, &audio));
        let msb_path = write_temp("msb", &synthetic_dsf(8, &audio));

        let lsb = DsfDecoder::open(&lsb_path).unwrap();
        let msb = DsfDecoder::open(&msb_path).unwrap();
        let _ = std::fs::remove_file(&lsb_path);
        let _ = std::fs::remove_file(&msb_path);

        assert_eq!(lsb.bit_order(), DsdBitOrder::LsbFirst);
        assert_eq!(msb.bit_order(), DsdBitOrder::MsbFirst);
    }

    #[test]
    fn padded_tail_and_metadata_are_not_audio_and_seek_is_exact() {
        let mut audio = vec![0xff; 8192];
        audio[..4].copy_from_slice(&[1, 2, 3, 4]);
        audio[4096..4100].copy_from_slice(&[11, 12, 13, 14]);
        let mut bytes = synthetic_dsf(1, &audio);
        put_u64(&mut bytes, 64, 32);
        let end = bytes.len() as u64;
        put_u64(&mut bytes, 20, end);
        bytes.extend_from_slice(b"ID3 metadata is not DSD");
        let size = bytes.len() as u64;
        put_u64(&mut bytes, 12, size);
        let path = write_temp("tail_seek", &bytes);
        let mut decoder = DsfDecoder::open(&path).unwrap();
        let mut output = [0u8; 20];
        assert_eq!(decoder.read_dsd_bytes(&mut output).unwrap(), 8);
        assert_eq!(&output[..8], &[1, 11, 2, 12, 3, 13, 4, 14]);
        assert_eq!(decoder.read_dsd_bytes(&mut output).unwrap(), 0);
        decoder.seek(8).unwrap();
        assert_eq!(decoder.read_dsd_bytes(&mut output).unwrap(), 6);
        assert_eq!(&output[..6], &[2, 12, 3, 13, 4, 14]);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn stale_outer_file_sizes_do_not_truncate_valid_audio() {
        let audio = vec![0x69; 8192];
        let original = synthetic_dsf(1, &audio);
        let actual_size = original.len() as u64;
        for (index, declared) in [0, 92, actual_size - 128, actual_size + 1024].into_iter().enumerate() {
            let mut bytes = original.clone();
            put_u64(&mut bytes, 12, declared);
            let path = write_temp(&format!("stale_size_{index}"), &bytes);
            let mut decoder = DsfDecoder::open(&path).unwrap();
            let mut output = vec![0u8; audio.len() + 16];
            assert_eq!(decoder.read_dsd_bytes(&mut output).unwrap(), audio.len());
            assert_eq!(&output[..audio.len()], audio.as_slice());
            assert!(decoder.is_finished());
            std::fs::remove_file(path).unwrap();
        }
    }

    #[test]
    fn removed_tag_with_stale_size_and_pointer_keeps_audio() {
        let audio = vec![0x69; 8192];
        let mut bytes = synthetic_dsf(1, &audio);
        let actual_size = bytes.len() as u64;
        put_u64(&mut bytes, 12, actual_size + 1024);
        put_u64(&mut bytes, 20, actual_size + 128);
        let path = write_temp("removed_tag", &bytes);
        let mut decoder = DsfDecoder::open(&path).unwrap();
        decoder.seek(decoder.total_samples() - 8).unwrap();
        let mut output = [0u8; 16];
        assert_eq!(decoder.read_dsd_bytes(&mut output).unwrap(), 2);
        assert_eq!(&output[..2], &[0x69, 0x69]);
        assert_eq!(decoder.read_dsd_bytes(&mut output).unwrap(), 0);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn metadata_inside_audio_is_rejected() {
        let mut bytes = synthetic_dsf(1, &[0x69; 8192]);
        put_u64(&mut bytes, 20, 100);
        let path = write_temp("overlapping_metadata", &bytes);
        let error = DsfDecoder::open(&path).err().unwrap();
        assert!(error.to_string().contains("metadata overlaps"));
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn declared_samples_still_require_complete_channel_blocks() {
        let mut bytes = synthetic_dsf(1, &[0x69; 8192]);
        put_u64(&mut bytes, 64, 4097 * 8);
        let path = write_temp("short_blocks", &bytes);
        let error = DsfDecoder::open(&path).err().unwrap();
        assert!(error.to_string().contains("required=16384, available=8192"));
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn truncated_channel_blocks_are_rejected() {
        let mut bytes = synthetic_dsf(1, &[0x69; 8192]);
        bytes.truncate(bytes.len() - 1);
        let actual_size = bytes.len() as u64;
        put_u64(&mut bytes, 12, actual_size);
        let path = write_temp("truncated", &bytes);
        let error = DsfDecoder::open(&path).err().unwrap();
        assert!(error.to_string().contains("Invalid or truncated DSF chunk"));
        std::fs::remove_file(path).unwrap();
    }

    fn synthetic_dsf_with_channels(channel_type: u32, channel_count: u32, audio: &[u8]) -> Vec<u8> {
        let mut bytes = synthetic_dsf(1, audio);
        put_u32(&mut bytes, 48, channel_type);
        put_u32(&mut bytes, 52, channel_count);
        let samples = (audio.len() as u64 / channel_count as u64) * 8;
        put_u64(&mut bytes, 64, samples);
        bytes
    }

    #[test]
    fn five_point_one_channel_type_is_exposed() {
        let audio = vec![0x69u8; 4096 * 6];
        let bytes = synthetic_dsf_with_channels(7, 6, &audio);
        let path = write_temp("channel_51", &bytes);
        let decoder = DsfDecoder::open(&path).unwrap();
        std::fs::remove_file(&path).unwrap();

        assert_eq!(decoder.channels(), 6);
        assert_eq!(
            decoder.channel_config(),
            Some(DsdChannelConfig::FivePointOne)
        );
    }

    #[test]
    fn mismatched_channel_type_falls_back_to_count_layout() {
        let audio = vec![0x69u8; 4096 * 4];
        let bytes = synthetic_dsf_with_channels(7, 4, &audio);
        let path = write_temp("channel_mismatch", &bytes);
        let decoder = DsfDecoder::open(&path).unwrap();
        std::fs::remove_file(&path).unwrap();

        assert_eq!(decoder.channels(), 4);
        assert_eq!(decoder.channel_config(), Some(DsdChannelConfig::Quad));
    }
}
