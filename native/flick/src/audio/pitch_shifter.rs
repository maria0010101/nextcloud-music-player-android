//! Pitch shifting is intentionally not part of the Nextcloud integration.
//! No SoundTouch code is linked. The public control API is not exported via JNI.
pub const MAX_SEMITONES: f32 = 0.0;
pub struct PitchShifter;
impl PitchShifter {
 pub fn new(_rate:u32,_channels:usize)->Self {Self}
 pub fn semitones(&self)->f32 {0.0}
 pub fn set_semitones(&mut self,_value:f32) {}
 pub fn reset(&mut self) {}
 pub fn process(&mut self,_data:&mut [f32],_channels:usize) {}
}
