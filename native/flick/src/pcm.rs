//! Symmetric PCM scaling. Signed PCM is normalized by 2^(N-1), not its positive maximum.
pub fn f32_to_linear_i32(sample: f32) -> i32 {
    if !sample.is_finite() {
        return 0;
    }
    ((sample.clamp(-1.0, 1.0) as f64 * 2_147_483_648.0).round())
        .clamp(i32::MIN as f64, i32::MAX as f64) as i32
}
/// Only integer widths exactly representable by the integrated float32 PCM path.
pub fn require_strict_precision(bits: u32) -> Result<(), String> {
    if matches!(bits, 16 | 24) {
        Ok(())
    } else {
        Err("Strict bit-perfect supports verified PCM16/24 sources only; PCM32 or unknown precision is not lossless through float32".into())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exhaustive_s16_roundtrip() {
        for s in i16::MIN..=i16::MAX {
            assert_eq!((f32_to_linear_i32(s as f32 / 32768.0) >> 16) as i16, s);
        }
    }
    #[test]
    fn s24_boundaries_and_random_roundtrip() {
        let mut v = 0x12345678u32;
        for s in [-8388608, -8388607, -1, 0, 1, 8388606, 8388607] {
            assert_eq!(f32_to_linear_i32(s as f32 / 8388608.0) >> 8, s);
        }
        for _ in 0..100000 {
            v = v.wrapping_mul(1664525).wrapping_add(1013904223);
            let s = (v as i32) >> 8;
            assert_eq!(f32_to_linear_i32(s as f32 / 8388608.0) >> 8, s);
        }
    }
    #[test]
    fn clipping_and_nonfinite() {
        assert_eq!(f32_to_linear_i32(-1.0), i32::MIN);
        assert_eq!(f32_to_linear_i32(1.0), i32::MAX);
        assert_eq!(f32_to_linear_i32(f32::NAN), 0);
    }
    #[test]
    fn strict_precision_never_admits_unknown_or_pcm32() {
        assert!(require_strict_precision(16).is_ok());
        assert!(require_strict_precision(24).is_ok());
        for bits in [0, 8, 20, 32, 64] {
            assert!(require_strict_precision(bits).is_err());
        }
    }
}
