use super::{DsdBitOrder, DsdChannelLayout, DsdFormatDecoder};
use anyhow::{anyhow, Result};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

pub struct DffDecoder {
    file: File,
    sample_rate: u32,
    channels: u16,
    total_samples: u64,
    data_offset: u64,
    current_position: u64,
    finished: bool,
}

impl DffDecoder {
    pub fn open(path: &Path) -> Result<Self> {
        let dff = dff_meta::DffFile::open(path)
            .map_err(|e| anyhow!("Failed to parse DFF header: {}", e))?;

        let sample_rate = dff
            .get_sample_rate()
            .map_err(|e| anyhow!("Failed to get DFF sample rate: {}", e))?;
        let channels =
            dff.get_num_channels()
                .map_err(|e| anyhow!("Failed to get DFF channel count: {}", e))?;
        let data_offset = dff.get_dsd_data_offset();
        let audio_length = dff.get_audio_length();
        if sample_rate == 0 || !(1..=32).contains(&channels) {
            return Err(anyhow!("Invalid DFF sample rate or channel count"));
        }
        if audio_length % channels as u64 != 0 {
            return Err(anyhow!("DFF audio ends in a partial channel frame"));
        }
        let total_samples = (audio_length / channels as u64)
            .checked_mul(8)
            .ok_or_else(|| anyhow!("DFF sample count overflow"))?;

        let mut file = File::open(path)?;
        let audio_end = data_offset.checked_add(audio_length)
            .ok_or_else(|| anyhow!("DFF audio boundary overflow"))?;
        if audio_end > file.metadata()?.len() {
            return Err(anyhow!("Truncated DFF audio chunk"));
        }
        file.seek(SeekFrom::Start(data_offset))?;

        drop(dff);

        Ok(Self {
            file,
            sample_rate,
            channels: channels as u16,
            total_samples,
            data_offset,
            current_position: 0,
            finished: total_samples == 0,
        })
    }
}

impl DsdFormatDecoder for DffDecoder {
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
        let sample = sample.min(self.total_samples) / 8 * 8;
        let byte_offset = (sample / 8) * self.channels as u64;
        self.file
            .seek(SeekFrom::Start(self.data_offset + byte_offset))?;
        self.current_position = sample;
        self.finished = sample == self.total_samples;
        Ok(())
    }

    fn read_dsd_bytes(&mut self, buf: &mut [u8]) -> Result<usize> {
        if self.finished {
            return Ok(0);
        }

        let channels = self.channels as usize;
        let remaining_frames = (self.total_samples - self.current_position) / 8;
        let frames = ((buf.len() / channels) as u64).min(remaining_frames);
        let bytes_read = frames as usize * channels;
        if bytes_read == 0 { return Ok(0); }
        // read_exact handles short/interrupted FUSE reads. Stop at the declared
        // DSD chunk, never at the physical EOF after trailing metadata.
        self.file.read_exact(&mut buf[..bytes_read])?;
        let samples_consumed = frames * 8;
        self.current_position += samples_consumed;

        if self.current_position >= self.total_samples {
            self.finished = true;
        }

        Ok(bytes_read)
    }

    fn is_finished(&self) -> bool {
        self.finished
    }

    fn channel_layout(&self) -> DsdChannelLayout {
        DsdChannelLayout::Interleaved
    }

    fn bit_order(&self) -> DsdBitOrder {
        DsdBitOrder::MsbFirst
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_and_seeks_do_not_include_trailing_metadata() {
        let path = std::env::temp_dir().join(format!("flick_dff_bounds_{}", std::process::id()));
        std::fs::write(&path, [1, 11, 2, 12, 3, 13, 0xff, 0xff]).unwrap();
        let mut decoder = DffDecoder {
            file: File::open(&path).unwrap(), sample_rate: 2_822_400, channels: 2,
            total_samples: 24, data_offset: 0, current_position: 0, finished: false,
        };
        let mut output = [0u8; 16];
        assert_eq!(decoder.read_dsd_bytes(&mut output).unwrap(), 6);
        assert_eq!(&output[..6], &[1, 11, 2, 12, 3, 13]);
        decoder.seek(9).unwrap();
        assert_eq!(decoder.read_dsd_bytes(&mut output).unwrap(), 4);
        assert_eq!(&output[..4], &[2, 12, 3, 13]);
        decoder.seek(u64::MAX).unwrap();
        assert!(decoder.is_finished());
        drop(decoder);
        std::fs::remove_file(path).unwrap();
    }
}
