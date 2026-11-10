pub mod dff_decoder;
pub mod dsf_decoder;
pub mod wavpack_decoder;

use anyhow::Result;
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DsdChannelLayout {
    SequentialBlocks { block_size: usize },
    Interleaved,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DsdBitOrder {
    LsbFirst,
    MsbFirst,
}

/// Known DSD channel arrangements, in the on-disk channel order.
///
/// DSF carries an explicit `channel_type` field; DFF/WavPack sources only
/// carry a channel count, so they fall back to the count-based mapping.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DsdChannelConfig {
    Mono,
    Stereo,
    ThreeChannels,
    Quad,
    FourChannels,
    FiveChannels,
    FivePointOne,
}

impl DsdChannelConfig {
    /// Count-based default mapping. `Quad` is the default for four channels;
    /// an explicit DSF `channel_type` disambiguates it from FL/FR/C/LFE.
    pub fn from_count(count: u16) -> Option<Self> {
        match count {
            1 => Some(Self::Mono),
            2 => Some(Self::Stereo),
            3 => Some(Self::ThreeChannels),
            4 => Some(Self::Quad),
            5 => Some(Self::FiveChannels),
            6 => Some(Self::FivePointOne),
            _ => None,
        }
    }

    /// DSF spec `channel_type` values (format chunk, absolute offset 20).
    pub fn from_channel_type(channel_type: u32) -> Option<Self> {
        match channel_type {
            1 => Some(Self::Mono),
            2 => Some(Self::Stereo),
            3 => Some(Self::ThreeChannels),
            4 => Some(Self::Quad),
            5 => Some(Self::FourChannels),
            6 => Some(Self::FiveChannels),
            7 => Some(Self::FivePointOne),
            _ => None,
        }
    }

    pub fn channels(self) -> u16 {
        match self {
            Self::Mono => 1,
            Self::Stereo => 2,
            Self::ThreeChannels => 3,
            Self::Quad | Self::FourChannels => 4,
            Self::FiveChannels => 5,
            Self::FivePointOne => 6,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Mono => "mono",
            Self::Stereo => "stereo",
            Self::ThreeChannels => "3ch (FL/FR/C)",
            Self::Quad => "quad (FL/FR/BL/BR)",
            Self::FourChannels => "4ch (FL/FR/C/LFE)",
            Self::FiveChannels => "5ch (FL/FR/C/BL/BR)",
            Self::FivePointOne => "5.1 (FL/FR/C/LFE/BL/BR)",
        }
    }
}

/// ITU-R BS.775-style stereo fold-down for known DSD channel layouts.
///
/// LFE is intentionally excluded from the mix per BS.775; it is an effects
/// channel, not part of the stereo presentation. The mix is normalized by the
/// sum of the absolute coefficients feeding each output, so a full-scale
/// multichannel source can never clip the stereo bus (roughly -4.6 dB for
/// 3ch/quad and -7.7 dB for 5ch/5.1). Input is interleaved at
/// `config.channels()` lanes; returns the number of samples written to
/// `output` (frames * 2), or 0 when the buffer is too small.
pub fn downmix_to_stereo(input: &[f32], config: DsdChannelConfig, output: &mut [f32]) -> usize {
    const M: f32 = std::f32::consts::FRAC_1_SQRT_2; // -3 dB
    let channels = config.channels() as usize;
    let frames = (input.len() / channels).min(output.len() / 2);
    for frame in 0..frames {
        let source = &input[frame * channels..(frame + 1) * channels];
        // `mixed` counts the M-weighted channels per output; the scale keeps
        // the worst-case (in-phase, all full-scale) sum at exactly 1.0.
        let (left, right, mixed) = match config {
            DsdChannelConfig::Mono => (source[0], source[0], 0),
            DsdChannelConfig::Stereo => (source[0], source[1], 0),
            DsdChannelConfig::ThreeChannels => (source[0] + M * source[2], source[1] + M * source[2], 1),
            DsdChannelConfig::Quad => (source[0] + M * source[2], source[1] + M * source[3], 1),
            DsdChannelConfig::FourChannels => (source[0] + M * source[2], source[1] + M * source[2], 1),
            DsdChannelConfig::FiveChannels => (
                source[0] + M * source[2] + M * source[3],
                source[1] + M * source[2] + M * source[4],
                2,
            ),
            DsdChannelConfig::FivePointOne => (
                source[0] + M * source[2] + M * source[4],
                source[1] + M * source[2] + M * source[5],
                2,
            ),
        };
        let scale = 1.0 / (1.0 + M * mixed as f32);
        output[frame * 2] = left * scale;
        output[frame * 2 + 1] = right * scale;
    }
    frames * 2
}

pub trait DsdFormatDecoder: Send {
    fn sample_rate(&self) -> u32;
    fn channels(&self) -> u16;
    fn total_samples(&self) -> u64;
    fn duration_secs(&self) -> f64;
    fn seek(&mut self, sample: u64) -> Result<()>;
    fn read_dsd_bytes(&mut self, buf: &mut [u8]) -> Result<usize>;
    fn is_finished(&self) -> bool;
    fn channel_layout(&self) -> DsdChannelLayout;
    fn bit_order(&self) -> DsdBitOrder;
    /// Known layout for stereo fold-down; count-based unless the container
    /// carries an explicit channel-type field (DSF).
    fn channel_config(&self) -> Option<DsdChannelConfig> {
        DsdChannelConfig::from_count(self.channels())
    }
}

pub fn open_dsd_decoder(path: &Path) -> Result<Box<dyn DsdFormatDecoder>> {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();

    match ext.as_str() {
        "dsf" => {
            let decoder = dsf_decoder::DsfDecoder::open(path)?;
            Ok(Box::new(decoder))
        }
        "dff" => {
            let decoder = dff_decoder::DffDecoder::open(path)?;
            Ok(Box::new(decoder))
        }
        "wv" => {
            let decoder = wavpack_decoder::WavpackDsdDecoder::open(path)?;
            Ok(Box::new(decoder))
        }
        _ => anyhow::bail!("Unsupported DSD format: {}", ext),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn count_and_type_mappings_cover_standard_layouts() {
        assert_eq!(DsdChannelConfig::from_count(1), Some(DsdChannelConfig::Mono));
        assert_eq!(DsdChannelConfig::from_count(2), Some(DsdChannelConfig::Stereo));
        assert_eq!(DsdChannelConfig::from_count(4), Some(DsdChannelConfig::Quad));
        assert_eq!(DsdChannelConfig::from_count(6), Some(DsdChannelConfig::FivePointOne));
        assert_eq!(DsdChannelConfig::from_count(7), None);

        assert_eq!(DsdChannelConfig::from_channel_type(4), Some(DsdChannelConfig::Quad));
        assert_eq!(
            DsdChannelConfig::from_channel_type(5),
            Some(DsdChannelConfig::FourChannels)
        );
        assert_eq!(
            DsdChannelConfig::from_channel_type(7),
            Some(DsdChannelConfig::FivePointOne)
        );
        assert_eq!(DsdChannelConfig::from_channel_type(0), None);
    }

    #[test]
    fn five_point_one_fold_down_uses_center_surrounds_and_drops_lfe() {
        // FL=1, FR=1, C=1, LFE=10, BL=1, BR=1; normalization leaves a
        // full-scale in-phase mix at exactly 1.0.
        let input = [1.0, 1.0, 1.0, 10.0, 1.0, 1.0];
        let mut output = [0.0f32; 2];
        let written = downmix_to_stereo(&input, DsdChannelConfig::FivePointOne, &mut output);
        assert_eq!(written, 2);
        assert!((output[0] - 1.0).abs() < 1e-6);
        assert!((output[1] - 1.0).abs() < 1e-6);

        // LFE must not leak into the mix: changing it cannot change the output.
        let mut lfe_zero = [0.0f32; 2];
        downmix_to_stereo(
            &[1.0, 1.0, 0.0, 0.0, 0.0, 0.0],
            DsdChannelConfig::FivePointOne,
            &mut lfe_zero,
        );
        let mut lfe_full = [0.0f32; 2];
        downmix_to_stereo(
            &[1.0, 1.0, 0.0, 10.0, 0.0, 0.0],
            DsdChannelConfig::FivePointOne,
            &mut lfe_full,
        );
        assert_eq!(lfe_zero, lfe_full);
    }

    #[test]
    fn fold_down_is_clip_safe_for_full_scale_sources() {
        for config in [
            DsdChannelConfig::ThreeChannels,
            DsdChannelConfig::Quad,
            DsdChannelConfig::FourChannels,
            DsdChannelConfig::FiveChannels,
            DsdChannelConfig::FivePointOne,
        ] {
            let channels = config.channels() as usize;
            for value in [1.0f32, -1.0] {
                let input = vec![value; channels];
                let mut output = vec![0.0f32; 2];
                downmix_to_stereo(&input, config, &mut output);
                for sample in output {
                    assert!(sample.abs() <= 1.0 + 1e-6, "{config:?} full scale overflowed");
                }
            }
            // Worst case for phase-aligned overs: all M-weighted lanes inverted.
            let mut input = vec![1.0f32; channels];
            for lane in 2..channels {
                input[lane] = -1.0;
            }
            let mut output = vec![0.0f32; 2];
            downmix_to_stereo(&input, config, &mut output);
            for sample in output {
                assert!(sample.abs() <= 1.0 + 1e-6, "{config:?} phase worst case overflowed");
            }
        }
    }

    #[test]
    fn four_channel_variants_route_center_and_rears_differently() {
        // Quad order: FL, FR, BL, BR.
        let mut quad_out = [0.0f32; 2];
        downmix_to_stereo(&[1.0, 1.0, 1.0, 1.0], DsdChannelConfig::Quad, &mut quad_out);
        assert!((quad_out[0] - 1.0).abs() < 1e-6);
        assert!((quad_out[1] - 1.0).abs() < 1e-6);

        // FL/FR/C/LFE order: LFE (index 3) must not leak into the mix.
        let mut four_out = [0.0f32; 2];
        downmix_to_stereo(
            &[1.0, 1.0, 1.0, 10.0],
            DsdChannelConfig::FourChannels,
            &mut four_out,
        );
        assert!((four_out[0] - 1.0).abs() < 1e-6);
        assert!((four_out[1] - 1.0).abs() < 1e-6);
    }

    #[test]
    fn stereo_and_mono_fold_down_stay_exact() {
        let mut stereo_out = [0.0f32; 4];
        downmix_to_stereo(&[0.25, -0.5, 0.75, -1.0], DsdChannelConfig::Stereo, &mut stereo_out);
        assert_eq!(stereo_out, [0.25, -0.5, 0.75, -1.0]);

        let mut mono_out = [0.0f32; 2];
        downmix_to_stereo(&[0.5], DsdChannelConfig::Mono, &mut mono_out);
        assert_eq!(mono_out, [0.5, 0.5]);
    }

    #[test]
    fn downmix_honors_output_capacity() {
        let input = [1.0f32; 12];
        let mut output = [0.0f32; 2];
        let written = downmix_to_stereo(&input, DsdChannelConfig::FivePointOne, &mut output);
        assert_eq!(written, 2);
    }
}
