use std::sync::Mutex;

use symphonia::core::audio::{layouts, AudioBuffer, AudioSpec, GenericAudioBufferRef};
use symphonia::core::codecs::audio::{
    well_known, AudioCodecParameters, AudioDecoder, AudioDecoderOptions, FinalizeResult,
};
use symphonia::core::codecs::CodecInfo;
use symphonia::core::errors::{decode_error, unsupported_error, Error, Result};
use symphonia::core::packet::PacketRef;

pub struct OpusDecoder {
    inner: Mutex<opus_sys::Decoder>,
    params: AudioCodecParameters,
    buf: Vec<f32>,
    decoded: AudioBuffer<f32>,
}

static CODEC_INFO: CodecInfo = CodecInfo {
    short_name: "opus",
    long_name: "Opus (via libopus)",
    profiles: &[],
};

impl OpusDecoder {
    pub fn try_new(params: &AudioCodecParameters, _options: &AudioDecoderOptions) -> Result<Self> {
        let channels = params.channels.as_ref().map(|c| c.count()).unwrap_or(2);
        let sample_rate = params.sample_rate.unwrap_or(48_000);

        let ch = match channels {
            1 => opus_sys::Channels::Mono,
            2 => opus_sys::Channels::Stereo,
            _ => return unsupported_error("opus: only mono and stereo are supported"),
        };

        let inner = opus_sys::Decoder::new(sample_rate, ch).or_else(|e| {
            Err(Error::IoError(std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("opus decoder init: error code {}", e),
            )))
        })?;

        let mut codec_params = AudioCodecParameters::new();
        codec_params
            .for_codec(well_known::CODEC_ID_OPUS)
            .with_sample_rate(sample_rate)
            .with_channels(
                params
                    .channels
                    .clone()
                    .unwrap_or(layouts::CHANNEL_LAYOUT_STEREO),
            );

        Ok(OpusDecoder {
            inner: Mutex::new(inner),
            params: codec_params,
            buf: Vec::new(),
            decoded: AudioBuffer::default(),
        })
    }
}

impl AudioDecoder for OpusDecoder {
    fn reset(&mut self) {
        if let Ok(inner) = self.inner.get_mut() {
            let _ = inner.reset_state();
        }
    }

    fn codec_info(&self) -> &CodecInfo {
        &CODEC_INFO
    }

    fn codec_params(&self) -> &AudioCodecParameters {
        &self.params
    }

    fn decode_ref(&mut self, packet: &PacketRef<'_>) -> Result<GenericAudioBufferRef<'_>> {
        let channels = self
            .params
            .channels
            .as_ref()
            .map(|c| c.count())
            .unwrap_or(2)
            .max(1);
        let data = packet.data;

        if data.is_empty() {
            let frames = channels * 960;
            self.buf.resize(frames, 0.0f32);
            if let Ok(inner) = self.inner.get_mut() {
                let _ = inner.decode_float(&[], &mut self.buf, true);
            }
        } else if let Ok(inner) = self.inner.get_mut() {
            let nb_samples = inner
                .get_nb_samples(data)
                .or_else(|_| decode_error("opus: failed to get sample count"))?;
            let total = nb_samples * channels;
            self.buf.resize(total, 0.0f32);
            inner
                .decode_float(data, &mut self.buf, false)
                .or_else(|_| decode_error("opus: decode failed"))?;
        } else {
            return decode_error("opus: mutex poisoned");
        }

        let n_frames = self.buf.len() / channels;
        let rate = self.params.sample_rate.unwrap_or(48_000);
        let spec_channels = self
            .params
            .channels
            .clone()
            .unwrap_or(layouts::CHANNEL_LAYOUT_STEREO);
        let spec = AudioSpec::new(rate, spec_channels);

        self.decoded = AudioBuffer::<f32>::new(spec, n_frames);
        self.decoded.render_with(Some(n_frames), |frame, planes| {
            for (ch, plane) in planes.iter_mut().enumerate() {
                plane[frame] = self.buf[frame * channels + ch];
            }
            Ok(())
        })?;

        Ok(GenericAudioBufferRef::F32(&self.decoded))
    }

    fn finalize(&mut self) -> FinalizeResult {
        FinalizeResult { verify_ok: None }
    }

    fn last_decoded(&self) -> GenericAudioBufferRef<'_> {
        GenericAudioBufferRef::F32(&self.decoded)
    }
}
