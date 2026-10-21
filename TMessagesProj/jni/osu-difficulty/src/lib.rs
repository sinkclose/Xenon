use jni::{JNIEnv, objects::{JByteArray, JClass, JIntArray}, sys::{jdouble, jstring, jdoubleArray, jlong, jboolean}};
use rosu_pp::{Beatmap, Difficulty, Performance};
use std::io::{self, Write, Cursor};

struct LimitedOutput(Vec<u8>, usize);
impl Write for LimitedOutput {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        if self.0.len() + data.len() > self.1 { return Err(io::Error::other("Replay data too large")); }
        self.0.extend_from_slice(data); Ok(data.len())
    }
    fn flush(&mut self) -> io::Result<()> { Ok(()) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_zxc_iconic_xenon_osu_OsuDifficulty_nativeReplayJson(
    env: JNIEnv, _class: JClass, data: JByteArray,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if env.get_array_length(&data).ok()? > 1024 * 1024 { return None; }
        let bytes = env.convert_byte_array(data).ok()?;
        if bytes.len() < 13 || u32::from_le_bytes(bytes[1..5].try_into().ok()?) > 16 * 1024 * 1024 { return None; }
        let mut output = LimitedOutput(Vec::new(), 2 * 1024 * 1024);
        lzma_rs::lzma_decompress(&mut Cursor::new(bytes), &mut output).ok()?;
        let text = String::from_utf8(output.0).ok()?;
        if !text.trim().starts_with('{') || !text.trim().ends_with('}') { return None; }
        Some(env.new_string(text).ok()?.into_raw())
    })).ok().flatten().unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_zxc_iconic_xenon_osu_OsuDifficulty_nativeReplayFrames(
    env: JNIEnv, _class: JClass, data: JByteArray,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if env.get_array_length(&data).ok()? > 16 * 1024 * 1024 { return None; }
        let bytes = env.convert_byte_array(data).ok()?;
        if bytes.len() < 13 || u32::from_le_bytes(bytes[1..5].try_into().ok()?) > 16 * 1024 * 1024 { return None; }
        let mut output = LimitedOutput(Vec::new(), 32 * 1024 * 1024);
        lzma_rs::lzma_decompress(&mut Cursor::new(bytes), &mut output).ok()?;
        let text = String::from_utf8(output.0).ok()?;
        Some(env.new_string(text).ok()?.into_raw())
    })).ok().flatten().unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_zxc_iconic_xenon_osu_OsuDifficulty_nativeReplayPerformance(
    env: JNIEnv, _class: JClass, data: JByteArray, mods: jlong, counts: JIntArray, lazer: jboolean, rate: jdouble,
) -> jdoubleArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if env.get_array_length(&data).ok()? > 8 * 1024 * 1024 || env.get_array_length(&counts).ok()? != 10 { return None; }
        let mut c = [0i32; 10]; env.get_int_array_region(counts, 0, &mut c).ok()?;
        if c.iter().any(|&n| n < 0) { return None; }
        let map = Beatmap::from_bytes(&env.convert_byte_array(data).ok()?).ok()?;
        if map.mode as i32 != c[9] { return None; }
        map.check_suspicion().ok()?;
        let mut perf = Performance::new(&map).mods(mods as u32).lazer(lazer != 0)
            .combo(c[0] as u32).n300(c[1] as u32).n100(c[2] as u32).n50(c[3] as u32)
            .misses(c[4] as u32).n_geki(c[5] as u32).n_katu(c[6] as u32)
            .large_tick_hits(c[7] as u32).slider_end_hits(c[8] as u32);
        if rate > 0.0 { perf = perf.clock_rate(rate); }
        let result = perf.checked_calculate().ok()?;
        let diff = result.difficulty_attributes();
        let values = [diff.stars(), result.pp(), diff.max_combo() as f64];
        if !values.iter().all(|v| v.is_finite()) { return None; }
        let array = env.new_double_array(3).ok()?;
        env.set_double_array_region(&array, 0, &values).ok()?;
        Some(array.into_raw())
    })).ok().flatten().unwrap_or(std::ptr::null_mut())
}

fn stars(bytes: &[u8]) -> Option<f64> {
    if bytes.len() > 8 * 1024 * 1024 { return None; }
    let map = Beatmap::from_bytes(bytes).ok()?;
    let attributes = Difficulty::new().checked_calculate(&map).ok()?;
    let value = attributes.stars();
    (value.is_finite() && value >= 0.0).then_some(value)
}

/// No mods, original game mode, default osu!lazer difficulty settings.
#[unsafe(no_mangle)]
pub extern "system" fn Java_zxc_iconic_xenon_osu_OsuDifficulty_nativeStars(
    env: JNIEnv, _class: JClass, data: JByteArray,
) -> jdouble {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if env.get_array_length(&data).ok()? > 8 * 1024 * 1024 { return None; }
        stars(&env.convert_byte_array(data).ok()?)
    })).ok().flatten().unwrap_or(f64::NAN)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_oversized_map() { assert!(stars(&vec![0; 8 * 1024 * 1024 + 1]).is_none()); }
    #[test]
    fn empty_map_has_finite_low_rating() {
        let map = b"osu file format v14\n[General]\nMode:0\n[Difficulty]\nCircleSize:4\nOverallDifficulty:8\nApproachRate:9\nSliderMultiplier:1.4\nSliderTickRate:1\n[TimingPoints]\n0,500,4,2,0,100,1,0\n[HitObjects]\n";
        let rating = stars(map).unwrap();
        assert!(rating >= 0.0 && rating < 1.0);
    }
}
