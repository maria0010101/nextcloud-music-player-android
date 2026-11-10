pub mod coefficients;
pub mod dop;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DsdOutputMode {
    PcmDecimation,
    Dop,
    Native,
    Auto,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DsdRate {
    Dsd64,
    Dsd128,
    Dsd256,
    Dsd512,
}

impl DsdRate {
    /// Highest PCM rate the software DSD decimator targets. Rates above this
    /// (352.8/705.6 kHz) are expensive and poorly supported by Android output
    /// paths; the output chain resamples from this rate when necessary.
    pub const MAX_PCM_TARGET: u32 = 176_400;

    pub fn sample_rate(&self) -> u32 {
        match self {
            Self::Dsd64 => 2_822_400,
            Self::Dsd128 => 5_644_800,
            Self::Dsd256 => 11_289_600,
            Self::Dsd512 => 22_579_200,
        }
    }

    pub fn from_sample_rate(rate: u32) -> Option<Self> {
        match rate {
            2_822_400 => Some(Self::Dsd64),
            5_644_800 => Some(Self::Dsd128),
            11_289_600 => Some(Self::Dsd256),
            22_579_200 => Some(Self::Dsd512),
            _ => None,
        }
    }

    pub fn byte_rate(&self) -> u32 {
        self.sample_rate() / 8
    }

    pub fn dop_carrier_rate(&self) -> u32 {
        match self {
            Self::Dsd64 => 176_400,
            Self::Dsd128 => 352_800,
            Self::Dsd256 => 705_600,
            Self::Dsd512 => 1_411_200,
        }
    }

    pub fn dop_bits_per_frame(&self) -> u8 {
        // Standard DoP always carries 16 DSD bits plus an eight-bit marker.
        // A 32-bit PCM container adds padding, not another DSD byte.
        24
    }

    pub fn dsd_bytes_per_channel_per_dop_frame(&self) -> usize {
        ((self.dop_bits_per_frame() - 8) / 8) as usize
    }

    pub fn pcm_decimation_targets(&self) -> &'static [u32] {
        static DSD64: &[u32] = &[176_400, 88_200, 44_100];
        static DSD128: &[u32] = &[352_800, 176_400, 88_200, 44_100];
        static DSD256: &[u32] = &[705_600, 352_800, 176_400, 88_200];
        static DSD512: &[u32] = &[705_600, 352_800, 176_400, 88_200];
        match self {
            Self::Dsd64 => DSD64,
            Self::Dsd128 => DSD128,
            Self::Dsd256 => DSD256,
            Self::Dsd512 => DSD512,
        }
    }

    pub fn best_pcm_target(&self, engine_rate: u32) -> u32 {
        let targets = self.pcm_decimation_targets();
        let ceiling = if engine_rate == 0 {
            Self::MAX_PCM_TARGET
        } else {
            engine_rate.min(Self::MAX_PCM_TARGET)
        };

        // Targets are ordered highest-first. Match the engine exactly when
        // possible, otherwise use the highest family rate that fits below it.
        // The DoP carrier is deliberately ignored: it is a wire-format rate,
        // not a requirement for PCM decimation, and its 352.8/705.6 kHz values
        // make the decimator run far above what managed Android outputs need.
        if let Some(&target) = targets.iter().find(|&&target| target <= ceiling) {
            return target;
        }

        // Engine rate is below the 44.1 kHz family: decode at the lowest
        // family rate and let the output chain resample down.
        targets.last().copied().unwrap_or(44_100)
    }
}

pub fn resolve_dsd_pcm_sample_rate(dsd_rate: DsdRate, engine_rate: u32) -> u32 {
    dsd_rate.best_pcm_target(engine_rate)
}

pub struct DsdDecimationPipeline {
    dsd_rate: DsdRate,
    target_pcm_rate: u32,
    channels: usize,
    /// Reverse each byte before the MSB-first bit walk. DSF stores
    /// LSB-first by default; the decimator consumes MSB-first, so the
    /// bytes must be flipped to read the stream in its original order.
    reverse_bits: bool,
    cic_decimation: usize,
    cic_int_c: Vec<i64>,
    cic_int_f: Vec<f64>,
    cic_comb_c: Vec<i64>,
    cic_comb_f: Vec<f64>,
    /// Per-channel FIR history, duplicated back-to-back so the active window
    /// is always contiguous. Avoids rotating a 512-tap buffer per input sample.
    fir_state: Vec<Vec<f64>>,
    fir_pos: Vec<usize>,
    /// Reversed (oldest-first) and normalization-scaled FIR coefficients.
    stage2_coeffs: Vec<f64>,
    cic_buffer: Vec<f64>,
    channel_buffers: Vec<Vec<f32>>,
}

impl DsdDecimationPipeline {
    const CIC_ORDER: usize = 3;
    const FIXED_FIR_TAPS: usize = 512;
    const MAX_CIC_DECIMATION: usize = 4;
    const AUDIO_BAND_HZ: u32 = 18_000;

    pub fn new(
        dsd_rate: DsdRate,
        target_pcm_rate: u32,
        channels: usize,
        reverse_bits: bool,
    ) -> Self {
        let total_decimation = dsd_rate.sample_rate() / target_pcm_rate.max(1);
        let cic_decimation = Self::compute_cic_decimation(total_decimation);

        let intermediate_rate = dsd_rate.sample_rate() / cic_decimation as u32;
        let cutoff = Self::AUDIO_BAND_HZ.min(intermediate_rate / 2);
        let mut stage2_coeffs = coefficients::generate_sinc_filter(
            Self::FIXED_FIR_TAPS,
            cutoff as f64 / intermediate_rate as f64,
            coefficients::WindowFunction::Kaiser { beta: 10.0 },
        );
        // The contiguous FIR window runs oldest -> newest. Reverse the
        // canonical newest-first taps to match it, and fold the CIC gain
        // normalization into the taps so the hot path needs only one dot
        // product per output sample.
        stage2_coeffs.reverse();
        let norm = 1.0 / (cic_decimation as f64).powi(Self::CIC_ORDER as i32);
        for coeff in &mut stage2_coeffs {
            *coeff *= norm;
        }

        Self {
            dsd_rate,
            target_pcm_rate,
            channels,
            reverse_bits,
            cic_decimation,
            cic_int_c: vec![0; channels * Self::CIC_ORDER],
            cic_int_f: vec![0.0; channels * Self::CIC_ORDER],
            cic_comb_c: vec![0; channels * Self::CIC_ORDER],
            cic_comb_f: vec![0.0; channels * Self::CIC_ORDER],
            fir_state: vec![vec![0.0; Self::FIXED_FIR_TAPS * 2]; channels],
            fir_pos: vec![0; channels],
            stage2_coeffs,
            cic_buffer: Vec::new(),
            channel_buffers: vec![Vec::new(); channels],
        }
    }

    fn compute_cic_decimation(total_decimation: u32) -> usize {
        if total_decimation == 0 {
            return 1;
        }
        let mut best = 1usize;
        for factor in (1..=Self::MAX_CIC_DECIMATION).rev() {
            if total_decimation as usize % factor == 0 {
                best = factor;
                break;
            }
        }
        if best < 2 {
            // fallback: accept any factor, round FIR decimation
            best = 1;
        }
        best
    }

    pub fn target_pcm_rate(&self) -> u32 {
        self.target_pcm_rate
    }

    pub fn dsd_rate(&self) -> DsdRate {
        self.dsd_rate
    }

    pub fn stage2_decimation(&self) -> usize {
        let total = self.dsd_rate.sample_rate() / self.target_pcm_rate.max(1);
        total as usize / self.cic_decimation.max(1)
    }

    fn cic_normalization(&self) -> f64 {
        1.0 / (self.cic_decimation as f64).powi(Self::CIC_ORDER as i32)
    }

    #[inline]
    fn dot_product(history: &[f64], coeffs: &[f64]) -> f64 {
        debug_assert_eq!(history.len(), coeffs.len());

        // Four independent accumulators give the CPU enough instruction-level
        // parallelism without reassociating floating-point math.
        let mut acc0 = 0.0f64;
        let mut acc1 = 0.0f64;
        let mut acc2 = 0.0f64;
        let mut acc3 = 0.0f64;
        let mut i = 0usize;
        while i + 4 <= history.len() {
            acc0 += history[i] * coeffs[i];
            acc1 += history[i + 1] * coeffs[i + 1];
            acc2 += history[i + 2] * coeffs[i + 2];
            acc3 += history[i + 3] * coeffs[i + 3];
            i += 4;
        }
        while i < history.len() {
            acc0 += history[i] * coeffs[i];
            i += 1;
        }
        (acc0 + acc1) + (acc2 + acc3)
    }

    pub fn process_bytes(
        &mut self,
        dsd_bytes: &[u8],
        channel_data_offsets: &[usize],
        output: &mut Vec<f32>,
    ) {
        let stage2_decimation = self.stage2_decimation();
        let bytes_per_channel_block = dsd_bytes.len() / self.channels.max(1);

        let bits_per_channel = bytes_per_channel_block * 8;
        let cic_out_count = bits_per_channel / self.cic_decimation;
        let mut cic_buffer = std::mem::take(&mut self.cic_buffer);
        cic_buffer.resize(cic_out_count, 0.0);
        output.clear();

        let norm = self.cic_normalization();

        for ch in 0..self.channels {
            let ch_offset = channel_data_offsets
                .get(ch)
                .copied()
                .unwrap_or(ch * bytes_per_channel_block);
            let ch_bytes = &dsd_bytes[ch_offset..ch_offset + bytes_per_channel_block];

            self.run_cic_stage(ch, ch_bytes, &mut cic_buffer);

            let stage2_count = cic_buffer.len() / stage2_decimation;
            let coeffs = &self.stage2_coeffs;
            let fir_state = &mut self.fir_state[ch];
            let fir_pos = &mut self.fir_pos[ch];
            let mut pos = *fir_pos;
            let ch_output = &mut self.channel_buffers[ch];
            ch_output.clear();
            ch_output.reserve(stage2_count);

            for i in 0..stage2_count {
                let base = i * stage2_decimation;
                for j in 0..stage2_decimation {
                    let sample = cic_buffer[base + j];
                    fir_state[pos] = sample;
                    fir_state[pos + Self::FIXED_FIR_TAPS] = sample;
                    pos += 1;
                    if pos == Self::FIXED_FIR_TAPS {
                        pos = 0;
                    }
                }

                // `pos` now points at the oldest of the last 512 samples; the
                // mirrored buffer makes that window contiguous for the dot
                // product instead of shifting the whole history every sample.
                let window = &fir_state[pos..pos + Self::FIXED_FIR_TAPS];
                ch_output.push(Self::dot_product(window, coeffs) as f32);
            }
            *fir_pos = pos;
        }

        self.cic_buffer = cic_buffer;

        let frames = self.channel_buffers.first().map_or(0, |c| c.len());
        output.reserve(frames * self.channels);
        for i in 0..frames {
            for ch in 0..self.channels {
                output.push(self.channel_buffers[ch][i]);
            }
        }

        if log::log_enabled!(log::Level::Debug) && !output.is_empty() {
            let peak = output.iter().map(|s| s.abs()).fold(0.0f32, f32::max);
            log::debug!(
                "[DSD-PCM] dsd={:?} target={} cic_dec={} s2_dec={} norm={:.6} peak={:.4} frames={}",
                self.dsd_rate,
                self.target_pcm_rate,
                self.cic_decimation,
                stage2_decimation,
                norm,
                peak,
                frames,
            );
        }
    }

    #[inline]
    fn add_f64(c: &mut i64, f: &mut f64, rhs: f64) {
        let full = *f + rhs;
        let carry = full.trunc();
        *c += carry as i64;
        *f = full - carry;
    }

    #[inline]
    fn add_pair(c: &mut i64, f: &mut f64, rhs_c: i64, rhs_f: f64) {
        *c += rhs_c;
        let full = *f + rhs_f;
        let carry = full.trunc();
        *c += carry as i64;
        *f = full - carry;
    }

    #[inline]
    fn sub_pair(c: &mut i64, f: &mut f64, rhs_c: i64, rhs_f: f64) {
        *c -= rhs_c;
        let full = *f - rhs_f;
        let carry = full.trunc();
        *c += carry as i64;
        *f = full - carry;
    }

    fn run_cic_stage(&mut self, channel: usize, bytes: &[u8], output: &mut [f64]) {
        let order = Self::CIC_ORDER;
        let integ_base = channel * order;
        let comb_base = channel * order;
        let dec = self.cic_decimation;
        let mut bit_idx = 0usize;

        for &raw_byte in bytes.iter() {
            let byte = if self.reverse_bits {
                raw_byte.reverse_bits()
            } else {
                raw_byte
            };
            for shift in (0..8).rev() {
                let bit_val = if (byte >> shift) & 1 == 1 {
                    1.0f64
                } else {
                    -1.0f64
                };

                Self::add_f64(
                    &mut self.cic_int_c[integ_base],
                    &mut self.cic_int_f[integ_base],
                    bit_val,
                );

                for stage in 1..order {
                    let prev_c = self.cic_int_c[integ_base + stage - 1];
                    let prev_f = self.cic_int_f[integ_base + stage - 1];
                    Self::add_pair(
                        &mut self.cic_int_c[integ_base + stage],
                        &mut self.cic_int_f[integ_base + stage],
                        prev_c,
                        prev_f,
                    );
                }

                bit_idx += 1;
                if bit_idx % dec == 0 {
                    let out_idx = bit_idx / dec - 1;
                    if out_idx < output.len() {
                        let mut val_c = self.cic_int_c[integ_base + order - 1];
                        let mut val_f = self.cic_int_f[integ_base + order - 1];

                        for stage in 0..order {
                            let idx = comb_base + stage;
                            let old_c = self.cic_comb_c[idx];
                            let old_f = self.cic_comb_f[idx];

                            self.cic_comb_c[idx] = val_c;
                            self.cic_comb_f[idx] = val_f;

                            Self::sub_pair(&mut val_c, &mut val_f, old_c, old_f);
                        }

                        output[out_idx] = val_c as f64 + val_f;
                    }
                }
            }
        }
    }

    pub fn reset(&mut self) {
        self.cic_int_c.fill(0);
        self.cic_int_f.fill(0.0);
        self.cic_comb_c.fill(0);
        self.cic_comb_f.fill(0.0);
        for state in &mut self.fir_state {
            state.fill(0.0);
        }
        self.fir_pos.fill(0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f64::consts::PI;

    fn tone_magnitude(samples: &[f32], sample_rate: u32, freq_hz: f64) -> f64 {
        let omega = 2.0 * PI * freq_hz / sample_rate as f64;
        let mut real = 0.0f64;
        let mut imag = 0.0f64;

        for (index, &sample) in samples.iter().enumerate() {
            let phase = omega * index as f64;
            real += sample as f64 * phase.cos();
            imag -= sample as f64 * phase.sin();
        }

        2.0 * real.hypot(imag) / samples.len() as f64
    }

    fn detect_tone_frequency(
        samples: &[f32],
        sample_rate: u32,
        center_hz: f64,
        radius_hz: f64,
        step_hz: f64,
    ) -> (f64, f64) {
        let mut best_freq = center_hz - radius_hz;
        let mut best_mag = f64::NEG_INFINITY;
        let steps = ((2.0 * radius_hz) / step_hz).round() as usize;

        for step in 0..=steps {
            let freq = center_hz - radius_hz + step as f64 * step_hz;
            let magnitude = tone_magnitude(samples, sample_rate, freq);
            if magnitude > best_mag {
                best_freq = freq;
                best_mag = magnitude;
            }
        }

        (best_freq, best_mag)
    }

    /// First-order sigma-delta PDM stream for a sine, packed MSB-first.
    fn dsd_sine_bytes(freq_hz: f64, dsd_rate: u32, seconds: f64, amplitude: f64) -> Vec<u8> {
        let bits = (dsd_rate as f64 * seconds) as usize;
        let mut bytes = vec![0u8; bits / 8];
        let mut integrator = 0.0f64;
        let mut previous = 1.0f64;

        for index in 0..bits {
            let phase = 2.0 * PI * freq_hz * index as f64 / dsd_rate as f64;
            integrator += amplitude * phase.sin() - previous;
            previous = if integrator >= 0.0 { 1.0 } else { -1.0 };
            if previous > 0.0 {
                bytes[index / 8] |= 0x80 >> (index % 8);
            }
        }

        bytes
    }

    #[test]
    fn reverse_bits_reads_lsb_first_stream_msb_first() {
        // 0x80 walked MSB-first and 0x01 walked with per-byte reversal
        // produce the same bit sequence, so both pipelines must agree
        // sample-for-sample.
        let msb_source = vec![0x80u8; 4096];
        let lsb_source = vec![0x01u8; 4096];

        let mut msb_pipeline =
            DsdDecimationPipeline::new(DsdRate::Dsd64, 44_100, 1, false);
        let mut lsb_pipeline =
            DsdDecimationPipeline::new(DsdRate::Dsd64, 44_100, 1, true);

        let mut msb_out = Vec::new();
        let mut lsb_out = Vec::new();
        msb_pipeline.process_bytes(&msb_source, &[0], &mut msb_out);
        lsb_pipeline.process_bytes(&lsb_source, &[0], &mut lsb_out);

        assert!(!msb_out.is_empty());
        assert_eq!(msb_out, lsb_out);
    }

    #[test]
    fn full_scale_bits_decimate_to_full_scale_pcm() {
        let bytes = vec![0xFFu8; 65_536];
        let mut pipeline = DsdDecimationPipeline::new(DsdRate::Dsd64, 44_100, 1, false);
        let mut output = Vec::new();
        pipeline.process_bytes(&bytes, &[0], &mut output);

        let tail = &output[2_048..];
        let mean = tail.iter().map(|&sample| sample as f64).sum::<f64>() / tail.len() as f64;
        assert!(
            (mean - 1.0).abs() < 0.01,
            "0xFF stream should settle at +1.0, got {mean}"
        );

        let bytes = vec![0x00u8; 65_536];
        let mut pipeline = DsdDecimationPipeline::new(DsdRate::Dsd64, 44_100, 1, false);
        let mut output = Vec::new();
        pipeline.process_bytes(&bytes, &[0], &mut output);

        let tail = &output[2_048..];
        let mean = tail.iter().map(|&sample| sample as f64).sum::<f64>() / tail.len() as f64;
        assert!(
            (mean + 1.0).abs() < 0.01,
            "0x00 stream should settle at -1.0, got {mean}"
        );
    }

    #[test]
    fn decimation_preserves_tone_frequency_and_level() {
        let tone_hz = 1_000.0;
        let dsd_bytes = dsd_sine_bytes(tone_hz, DsdRate::Dsd64.sample_rate(), 0.25, 0.5);
        let mut pipeline = DsdDecimationPipeline::new(DsdRate::Dsd64, 44_100, 1, false);
        let mut output = Vec::new();
        pipeline.process_bytes(&dsd_bytes, &[0], &mut output);

        assert!(
            output.len() > 4_096,
            "expected enough PCM output, got {} samples",
            output.len()
        );
        let analysis = &output[2_048..];
        let (measured_hz, magnitude) =
            detect_tone_frequency(analysis, 44_100, tone_hz, 20.0, 0.5);
        assert!(
            (measured_hz - tone_hz).abs() < 1.0,
            "tone moved from {tone_hz} Hz to {measured_hz} Hz"
        );
        assert!(
            magnitude > 0.2,
            "tone level collapsed after decimation: {magnitude}"
        );
    }

    #[test]
    fn decimation_keeps_stereo_channels_independent() {
        let left_hz = 1_000.0;
        let right_hz = 3_000.0;
        let left = dsd_sine_bytes(left_hz, DsdRate::Dsd128.sample_rate(), 0.25, 0.5);
        let right = dsd_sine_bytes(right_hz, DsdRate::Dsd128.sample_rate(), 0.25, 0.5);
        let mut source = left.clone();
        source.extend_from_slice(&right);

        let mut pipeline = DsdDecimationPipeline::new(DsdRate::Dsd128, 176_400, 2, false);
        let mut output = Vec::new();
        pipeline.process_bytes(&source, &[0, left.len()], &mut output);

        assert!(
            output.len() > 8_192,
            "expected enough stereo PCM output, got {} samples",
            output.len()
        );
        let left_pcm: Vec<f32> = output.iter().step_by(2).copied().collect();
        let right_pcm: Vec<f32> = output.iter().skip(1).step_by(2).copied().collect();
        let (left_measured, left_magnitude) =
            detect_tone_frequency(&left_pcm[2_048..], 176_400, left_hz, 20.0, 0.5);
        let (right_measured, right_magnitude) =
            detect_tone_frequency(&right_pcm[2_048..], 176_400, right_hz, 20.0, 0.5);

        assert!(
            (left_measured - left_hz).abs() < 1.0,
            "left channel moved from {left_hz} Hz to {left_measured} Hz"
        );
        assert!(
            (right_measured - right_hz).abs() < 1.0,
            "right channel moved from {right_hz} Hz to {right_measured} Hz"
        );
        assert!(
            left_magnitude > 0.2 && right_magnitude > 0.2,
            "stereo tone levels collapsed: left={left_magnitude}, right={right_magnitude}"
        );
    }

    #[test]
    fn pcm_target_never_prefers_the_dop_carrier_above_the_cap() {
        assert_eq!(DsdRate::Dsd64.best_pcm_target(0), 176_400);
        assert_eq!(DsdRate::Dsd128.best_pcm_target(0), 176_400);
        assert_eq!(DsdRate::Dsd256.best_pcm_target(0), 176_400);
        assert_eq!(DsdRate::Dsd512.best_pcm_target(0), 176_400);

        // The DoP carrier for DSD128 is 352.8 kHz; PCM decimation must not
        // select it even though it appears in the target list.
        assert_eq!(DsdRate::Dsd128.best_pcm_target(352_800), 176_400);
        assert_eq!(DsdRate::Dsd256.best_pcm_target(705_600), 176_400);

        // Match the engine when it is already on a supported family rate,
        // otherwise pick the highest family rate below it.
        assert_eq!(DsdRate::Dsd128.best_pcm_target(176_400), 176_400);
        assert_eq!(DsdRate::Dsd128.best_pcm_target(96_000), 88_200);
        assert_eq!(DsdRate::Dsd128.best_pcm_target(48_000), 44_100);
        assert_eq!(DsdRate::Dsd128.best_pcm_target(44_100), 44_100);
    }
}
