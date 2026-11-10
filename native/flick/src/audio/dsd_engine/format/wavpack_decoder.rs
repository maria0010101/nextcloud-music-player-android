use super::{DsdBitOrder, DsdChannelLayout, DsdFormatDecoder};
use crate::audio::dsd_engine::dsd::DsdRate;
use anyhow::{anyhow, Result};
use std::ffi::CString;
use std::fs::File;
use std::os::raw::c_char;
use std::path::Path;
use wavpack_sys::*;

pub struct WavpackDsdDecoder {
    context: *mut WavpackContext,
    sample_rate: u32,
    channels: u16,
    // libwavpack counts native DSD "samples" as bytes per channel; the trait
    // contract (see DsfDecoder) wants bits.
    total_byte_samples: u64,
    total_samples: u64,
    current_byte_sample: u64,
    is_dsd: bool,
    dsd_rate: Option<DsdRate>,
    _file: File,
}

impl WavpackDsdDecoder {
    pub fn open(path: &Path) -> Result<Self> {
        let path_str = path
            .to_str()
            .ok_or_else(|| anyhow!("Invalid path encoding"))?;
        let c_path = CString::new(path_str).map_err(|_| anyhow!("Path contains null byte"))?;

        let _file = File::open(path)?;

        let mut error_buf = [0u8; 256];
        let context = unsafe {
            WavpackOpenFileInput(
                c_path.as_ptr(),
                error_buf.as_mut_ptr() as *mut c_char,
                OPEN_DSD_NATIVE as i32,
                0,
            )
        };

        if context.is_null() {
            let error_msg = unsafe {
                std::ffi::CStr::from_ptr(error_buf.as_ptr() as *const c_char)
                    .to_string_lossy()
                    .into_owned()
            };
            return Err(anyhow!("WavPack open failed: {}", error_msg));
        }

        let qmode = unsafe { WavpackGetQualifyMode(context) } as u32;
        let is_dsd = (qmode & QMODE_DSD_AUDIO) != 0;

        if !is_dsd {
            unsafe { WavpackCloseFile(context) };
            return Err(anyhow!("WavPack file is not DSD"));
        }

        let sample_rate = unsafe { WavpackGetNativeSampleRate(context) };
        let channels = unsafe { WavpackGetNumChannels(context) };
        let byte_samples = unsafe { WavpackGetNumSamples64(context) };
        let (channels, total_byte_samples, total_samples) =
            match validate_stream_metadata(sample_rate, channels, byte_samples) {
                Ok(metadata) => metadata,
                Err(error) => {
                    unsafe { WavpackCloseFile(context) };
                    return Err(error);
                }
            };

        let dsd_rate = DsdRate::from_sample_rate(sample_rate);

        Ok(Self {
            context,
            sample_rate,
            channels,
            total_byte_samples,
            total_samples,
            current_byte_sample: 0,
            is_dsd,
            dsd_rate,
            _file,
        })
    }

    pub fn is_dsd(&self) -> bool {
        self.is_dsd
    }

    pub fn dsd_rate(&self) -> Option<DsdRate> {
        self.dsd_rate
    }
}

impl DsdFormatDecoder for WavpackDsdDecoder {
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
        // sample is DSD bits per channel; libwavpack wants bytes
        let target = sample.min(self.total_samples) / 8;
        if target == self.total_byte_samples {
            self.current_byte_sample = target;
            return Ok(());
        }
        let result = unsafe { WavpackSeekSample64(self.context, target as i64) };
        if result == 0 {
            return Err(anyhow!("WavPack seek failed"));
        }
        self.current_byte_sample = target;
        Ok(())
    }

    fn read_dsd_bytes(&mut self, buf: &mut [u8]) -> Result<usize> {
        if !self.is_dsd {
            return Err(anyhow!("WavPackDsdDecoder used for non-DSD WavPack file"));
        }

        let channels = self.channels as usize;
        if self.is_finished() || buf.len() < channels {
            return Ok(0);
        }

        // One libwavpack "sample" spans all channels; DSD bytes land interleaved
        // in the low 8 bits of each 32-bit word.
        let samples_to_read = ((buf.len() / channels) as u64)
            .min(self.total_byte_samples - self.current_byte_sample)
            .min(u32::MAX as u64) as u32;
        let mut int_buf = vec![0i32; samples_to_read as usize * channels];
        let unpacked =
            unsafe { WavpackUnpackSamples(self.context, int_buf.as_mut_ptr(), samples_to_read) };

        // libwavpack may substitute silence for a damaged DSD block. Do not
        // pass those replacement bytes off as an unchanged source payload.
        if unsafe { WavpackGetNumErrors(self.context) } != 0 {
            return Err(anyhow!("WavPack DSD checksum/decoder error"));
        }
        if unpacked == 0 {
            return Err(anyhow!("WavPack DSD ended before its declared sample count"));
        }
        if unpacked > samples_to_read {
            return Err(anyhow!("WavPack DSD returned more samples than requested"));
        }

        let bytes_read = unpacked as usize * channels;
        for (dst, src) in buf.iter_mut().take(bytes_read).zip(int_buf.iter()) {
            *dst = *src as u8;
        }
        self.current_byte_sample += unpacked as u64;

        Ok(bytes_read)
    }

    fn is_finished(&self) -> bool {
        self.context.is_null() || self.current_byte_sample >= self.total_byte_samples
    }

    fn channel_layout(&self) -> DsdChannelLayout {
        DsdChannelLayout::Interleaved
    }

    fn bit_order(&self) -> DsdBitOrder {
        // OPEN_DSD_NATIVE always emits MSB-first bytes. QMODE_DSD_LSB_FIRST
        // describes the original container, not the normalized decoder output.
        DsdBitOrder::MsbFirst
    }
}

impl Drop for WavpackDsdDecoder {
    fn drop(&mut self) {
        if !self.context.is_null() {
            unsafe {
                WavpackCloseFile(self.context);
            }
        }
    }
}

unsafe impl Send for WavpackDsdDecoder {}

fn validate_stream_metadata(rate: u32, channels: i32, byte_samples: i64) -> Result<(u16, u64, u64)> {
    if rate == 0 || !(1..=32).contains(&channels) || byte_samples < 0 {
        return Err(anyhow!("Invalid or unknown WavPack DSD stream metadata"));
    }
    let byte_samples = byte_samples as u64;
    let samples = byte_samples.checked_mul(8)
        .ok_or_else(|| anyhow!("WavPack DSD sample count overflow"))?;
    Ok((channels as u16, byte_samples, samples))
}

#[cfg(test)]
mod tests {
    use super::validate_stream_metadata;

    #[test]
    fn validates_native_byte_counts_before_conversion() {
        assert_eq!(validate_stream_metadata(2_822_400, 2, 3).unwrap(), (2, 3, 24));
        assert!(validate_stream_metadata(2_822_400, 2, -1).is_err());
        assert!(validate_stream_metadata(2_822_400, 65_536, 3).is_err());
        assert!(validate_stream_metadata(0, 2, 3).is_err());
        assert!(validate_stream_metadata(2_822_400, 2, i64::MAX).is_err());
    }
}
