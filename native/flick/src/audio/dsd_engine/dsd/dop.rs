use super::DsdRate;

pub struct DopPacker {
    dsd_rate: DsdRate,
    channels: usize,
    marker_state: u8,
    dsd_bytes_per_ch: usize,
    needs_bit_reverse: bool,
    pending: Vec<u8>,
}

impl DopPacker {
    pub fn new(dsd_rate: DsdRate, channels: usize) -> Self {
        Self::with_bit_reverse(dsd_rate, channels, false)
    }

    /// `needs_bit_reverse` covers sources stored LSB-first (DSF): the DoP
    /// wire is MSB-first, so bytes must be reversed before packing.
    pub fn with_bit_reverse(
        dsd_rate: DsdRate,
        channels: usize,
        needs_bit_reverse: bool,
    ) -> Self {
        let dsd_bytes_per_ch = dsd_rate.dsd_bytes_per_channel_per_dop_frame();
        Self {
            dsd_rate,
            channels,
            marker_state: 0x05,
            dsd_bytes_per_ch,
            needs_bit_reverse,
            pending: Vec::with_capacity(channels),
        }
    }

    pub fn carrier_rate(&self) -> u32 {
        self.dsd_rate.dop_carrier_rate()
    }

    pub fn bits_per_frame(&self) -> u8 {
        self.dsd_rate.dop_bits_per_frame()
    }

    pub fn dsd_bytes_per_ch_per_frame(&self) -> usize {
        self.dsd_bytes_per_ch
    }

    pub fn pack_to_f32(
        &mut self,
        dsd_bytes: &[u8],
        channel_offsets: &[usize],
        output: &mut Vec<f32>,
    ) {
        self.pack_words(dsd_bytes, channel_offsets, output, f32::from_bits);
    }

    fn pack_words<T>(
        &mut self,
        dsd_bytes: &[u8],
        channel_offsets: &[usize],
        output: &mut Vec<T>,
        mut convert: impl FnMut(u32) -> T,
    ) {
        output.clear();

        let bytes_per_channel = dsd_bytes.len() / self.channels.max(1);
        let mut byte_index = 0;
        if !self.pending.is_empty() && bytes_per_channel > 0 {
            for ch in 0..self.channels {
                let base = channel_offsets.get(ch).copied().unwrap_or(ch * bytes_per_channel);
                let pair = [self.pending[ch], dsd_bytes[base]];
                output.push(convert(self.build_dop_word(&pair, 0)));
            }
            self.pending.clear();
            self.advance_marker();
            byte_index = 1;
        }
        while byte_index + 1 < bytes_per_channel {
            for ch in 0..self.channels {
                let ch_base = channel_offsets
                    .get(ch)
                    .copied()
                    .unwrap_or(ch * bytes_per_channel);
                let frame_byte_offset = ch_base + byte_index;

                let sample = self.build_dop_word(dsd_bytes, frame_byte_offset);

                output.push(convert(sample));
            }
            self.advance_marker();
            byte_index += 2;
        }
        if byte_index < bytes_per_channel {
            for ch in 0..self.channels {
                let base = channel_offsets.get(ch).copied().unwrap_or(ch * bytes_per_channel);
                self.pending.push(dsd_bytes[base + byte_index]);
            }
        }
    }

    /// Pack DoP frames into i32 samples for integer audio output (AAudio I32).
    /// Each sample is a left-justified 24-bit DoP word in a 32-bit container:
    /// bits 31:24 = marker, bits 23:8 = DSD data, bits 7:0 = zero.
    pub fn pack_to_i32(
        &mut self,
        dsd_bytes: &[u8],
        channel_offsets: &[usize],
        output: &mut Vec<i32>,
    ) {
        self.pack_words(dsd_bytes, channel_offsets, output, |word| word as i32);
    }

    fn build_dop_word(&self, dsd_bytes: &[u8], frame_byte_offset: usize) -> u32 {
        let bits = self.bits_per_frame() as usize;
        let dsd_data_bits = bits - 8;
        let mut sample: u32 = (self.marker_state as u32) << dsd_data_bits;

        for byte_idx in 0..self.dsd_bytes_per_ch {
            let read_pos = frame_byte_offset + byte_idx;
            if read_pos < dsd_bytes.len() {
                let shift = dsd_data_bits - 8 * (byte_idx + 1);
                let byte = dsd_bytes[read_pos];
                let byte = if self.needs_bit_reverse {
                    byte.reverse_bits()
                } else {
                    byte
                };
                sample |= (byte as u32) << shift;
            }
        }

        sample << (32 - bits)
    }

    fn advance_marker(&mut self) {
        self.marker_state = if self.marker_state == 0x05 {
            0xFA
        } else {
            0x05
        };
    }

    pub fn dop_frame_size(&self) -> usize {
        (1 + self.dsd_bytes_per_ch) * self.channels
    }

    pub fn reset(&mut self) {
        self.marker_state = 0x05;
        self.pending.clear();
    }

    /// Finish an odd byte tail with DSD silence, never with adjacent metadata
    /// or the next channel's bytes. Only the transport padding is synthetic.
    pub fn flush_to_f32(&mut self, output: &mut Vec<f32>) {
        output.clear();
        if self.pending.is_empty() {
            return;
        }
        let silence = if self.needs_bit_reverse { 0x69u8.reverse_bits() } else { 0x69 };
        for ch in 0..self.channels {
            output.push(f32::from_bits(self.build_dop_word(&[self.pending[ch], silence], 0)));
        }
        self.pending.clear();
        self.advance_marker();
    }
}

/// Marker phase belongs to the emitted stream, not individual decoders.
/// Reframe after pause/starvation/track changes without modifying DSD bytes.
pub(crate) fn frame_dop_f32(output: &mut [f32], channels: usize, marker_is_fa: &mut bool) {
    for frame in output.chunks_mut(channels.max(1)) {
        let marker = if *marker_is_fa { 0xFAu32 } else { 0x05 };
        for sample in frame {
            *sample = f32::from_bits((sample.to_bits() & 0x00ff_ffff) | marker << 24);
        }
        *marker_is_fa = !*marker_is_fa;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn split_byte_pairs_preserve_channels_and_payload() {
        let mut packer = DopPacker::new(DsdRate::Dsd64, 2);
        let mut output = Vec::new();
        packer.pack_to_f32(&[0x12, 0x34], &[0, 1], &mut output);
        assert!(output.is_empty());
        packer.pack_to_f32(&[0x56, 0x78], &[0, 1], &mut output);
        assert_eq!(output.iter().map(|s| s.to_bits()).collect::<Vec<_>>(), [0x05125600, 0x05347800]);
        packer.pack_to_f32(&[0xab, 0xcd], &[0, 1], &mut output);
        packer.flush_to_f32(&mut output);
        assert_eq!(output.iter().map(|s| s.to_bits()).collect::<Vec<_>>(), [0xfaab6900, 0xfacd6900]);
    }

    #[test]
    fn wire_markers_alternate_by_frame_across_buffers() {
        let mut phase = false;
        let mut first = [f32::from_bits(0xfa123400); 6];
        frame_dop_f32(&mut first, 2, &mut phase);
        assert_eq!(first.iter().map(|s| s.to_bits()).collect::<Vec<_>>(),
            [0x05123400, 0x05123400, 0xfa123400, 0xfa123400, 0x05123400, 0x05123400]);
        let mut second = [f32::from_bits(0x05696900); 2];
        frame_dop_f32(&mut second, 2, &mut phase);
        assert_eq!(second[0].to_bits(), 0xfa696900);
        assert_eq!(second[1].to_bits(), 0xfa696900);
    }

    #[test]
    fn integer_packing_retains_a_split_pair() {
        let mut packer = DopPacker::new(DsdRate::Dsd64, 2);
        let mut output = Vec::new();
        packer.pack_to_i32(&[0x12, 0x34], &[0, 1], &mut output);
        assert!(output.is_empty());
        packer.pack_to_i32(&[0x56, 0x78], &[0, 1], &mut output);
        assert_eq!(output, [0x05125600, 0x05347800]);
        assert_eq!(packer.dop_frame_size(), 6);
    }

    #[test]
    fn all_dop_rates_use_standard_two_byte_payloads() {
        for rate in [DsdRate::Dsd64, DsdRate::Dsd128, DsdRate::Dsd256, DsdRate::Dsd512] {
            assert_eq!(rate.dop_carrier_rate() * 16, rate.sample_rate());
            assert_eq!(rate.dop_bits_per_frame(), 24);
        }
    }
}
