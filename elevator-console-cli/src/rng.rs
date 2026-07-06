//! A tiny non-cryptographic PRNG (SplitMix64). Replaces the `rand` crate: the load simulator only
//! needs "pick a random elevator / floor", not statistical quality, so a 24-line generator saves a
//! handful of transitive dependencies (rand, getrandom, ppv-lite86, …) and shrinks the binary.
pub struct Rng(u64);

impl Rng {
    /// Seed the generator. Any seed is fine; 0 is remapped so the stream is never all-zero.
    pub fn seeded(seed: u64) -> Self {
        Rng(if seed == 0 { 0x9E3779B97F4A7C15 } else { seed })
    }

    fn next_u64(&mut self) -> u64 {
        // SplitMix64: one add + two xor-multiply-shift finalizer rounds.
        self.0 = self.0.wrapping_add(0x9E3779B97F4A7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
        z ^ (z >> 31)
    }

    /// Uniform-ish index in `0..n`. Caller guarantees `n > 0`. Modulo bias is negligible for the
    /// small ranges used here (fleet size, floor count).
    pub fn below(&mut self, n: usize) -> usize {
        (self.next_u64() % n as u64) as usize
    }

    /// A floor in `0..=max` (inclusive). `max` must be >= 0.
    pub fn floor(&mut self, max: i32) -> i32 {
        (self.next_u64() % (max as u64 + 1)) as i32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn below_stays_in_range() {
        let mut rng = Rng::seeded(42);
        for _ in 0..1000 {
            assert!(rng.below(7) < 7);
        }
    }

    #[test]
    fn floor_is_inclusive_and_bounded() {
        let mut rng = Rng::seeded(1);
        let mut saw_zero = false;
        let mut saw_max = false;
        for _ in 0..2000 {
            let f = rng.floor(3);
            assert!((0..=3).contains(&f));
            saw_zero |= f == 0;
            saw_max |= f == 3;
        }
        assert!(saw_zero && saw_max, "should cover both ends of 0..=max");
    }

    #[test]
    fn same_seed_same_stream() {
        let (mut a, mut b) = (Rng::seeded(99), Rng::seeded(99));
        for _ in 0..50 {
            assert_eq!(a.below(1000), b.below(1000));
        }
    }

    #[test]
    fn zero_seed_is_not_stuck() {
        let mut rng = Rng::seeded(0);
        assert_ne!(rng.below(u32::MAX as usize), rng.below(u32::MAX as usize));
    }
}
