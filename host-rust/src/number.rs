//! Canonical values for the independent host.
//!
//! Natural numbers and integers are arbitrary precision: they are stored as
//! their canonical minimal base-128 little endian chunk sequence, which is
//! exactly the payload of the canonical varint. Decoding, re-encoding,
//! ordering and decimal rendering therefore work for any magnitude.
//!
//! Arithmetic primitives operate on the `i128` window; a value outside that
//! window produces a canonical `number-out-of-range` failure rather than a
//! silent truncation.

use std::cmp::Ordering;

// --------------------------------------------------------------- naturals

/// A non negative integer held as minimal base-128 little endian chunks.
#[derive(Clone, PartialEq, Eq, Debug, Hash)]
pub struct Nat {
    pub chunks: Vec<u8>,
}

impl Nat {
    pub fn zero() -> Nat {
        Nat { chunks: vec![0] }
    }

    pub fn from_chunks(mut chunks: Vec<u8>) -> Nat {
        while chunks.len() > 1 && *chunks.last().unwrap() == 0 {
            chunks.pop();
        }
        if chunks.is_empty() {
            chunks.push(0);
        }
        Nat { chunks }
    }

    /// True when the chunk sequence is the minimal representation.
    pub fn is_minimal(&self) -> bool {
        !self.chunks.is_empty() && (self.chunks.len() == 1 || *self.chunks.last().unwrap() != 0)
    }

    pub fn from_u128(mut value: u128) -> Nat {
        let mut chunks = Vec::new();
        loop {
            chunks.push((value & 0x7f) as u8);
            value >>= 7;
            if value == 0 {
                break;
            }
        }
        Nat { chunks }
    }

    pub fn to_u128(&self) -> Option<u128> {
        let mut value: u128 = 0;
        for (index, chunk) in self.chunks.iter().enumerate() {
            let shift = 7u32 * index as u32;
            if shift >= 128 {
                if *chunk != 0 {
                    return None;
                }
                continue;
            }
            let part = (*chunk as u128).checked_shl(shift)?;
            value = value.checked_add(part)?;
        }
        Some(value)
    }

    pub fn is_zero(&self) -> bool {
        self.chunks.iter().all(|c| *c == 0)
    }

    pub fn is_even(&self) -> bool {
        self.chunks[0] & 1 == 0
    }

    /// n / 2, exact for even inputs and flooring for odd ones.
    pub fn halve(&self) -> Nat {
        let mut out = vec![0u8; self.chunks.len()];
        let mut carry = 0u8;
        for index in (0..self.chunks.len()).rev() {
            let value = (carry as u16) * 128 + self.chunks[index] as u16;
            out[index] = (value / 2) as u8;
            carry = (value % 2) as u8;
        }
        Nat::from_chunks(out)
    }

    pub fn double(&self) -> Nat {
        let mut out = Vec::with_capacity(self.chunks.len() + 1);
        let mut carry = 0u8;
        for chunk in &self.chunks {
            let value = (*chunk as u16) * 2 + carry as u16;
            out.push((value & 0x7f) as u8);
            carry = (value >> 7) as u8;
        }
        if carry > 0 {
            out.push(carry);
        }
        Nat::from_chunks(out)
    }

    pub fn add_one(&self) -> Nat {
        let mut out = self.chunks.clone();
        let mut carry = 1u8;
        for chunk in out.iter_mut() {
            let value = *chunk as u16 + carry as u16;
            *chunk = (value & 0x7f) as u8;
            carry = (value >> 7) as u8;
            if carry == 0 {
                break;
            }
        }
        if carry > 0 {
            out.push(carry);
        }
        Nat::from_chunks(out)
    }

    pub fn sub_one(&self) -> Nat {
        let mut out = self.chunks.clone();
        for chunk in out.iter_mut() {
            if *chunk > 0 {
                *chunk -= 1;
                break;
            }
            *chunk = 0x7f;
        }
        Nat::from_chunks(out)
    }

    pub fn to_decimal(&self) -> String {
        let mut digits: Vec<u8> = vec![0];
        for chunk in self.chunks.iter().rev() {
            let mut carry = *chunk as u32;
            for digit in digits.iter_mut() {
                let value = (*digit as u32) * 128 + carry;
                *digit = (value % 10) as u8;
                carry = value / 10;
            }
            while carry > 0 {
                digits.push((carry % 10) as u8);
                carry /= 10;
            }
        }
        while digits.len() > 1 && *digits.last().unwrap() == 0 {
            digits.pop();
        }
        digits.iter().rev().map(|d| (b'0' + d) as char).collect()
    }

    pub fn from_decimal(text: &str) -> Option<Nat> {
        if text.is_empty() || !text.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        // chunks hold base-128 little endian digits.
        let mut chunks: Vec<u8> = vec![0];
        for byte in text.bytes() {
            let mut carry = (byte - b'0') as u16;
            for chunk in chunks.iter_mut() {
                let value = (*chunk as u16) * 10 + carry;
                *chunk = (value & 0x7f) as u8;
                carry = value >> 7;
            }
            while carry > 0 {
                chunks.push((carry & 0x7f) as u8);
                carry >>= 7;
            }
        }
        Some(Nat::from_chunks(chunks))
    }
}

impl Ord for Nat {
    fn cmp(&self, other: &Nat) -> Ordering {
        let left = &self.chunks;
        let right = &other.chunks;
        if left.len() != right.len() {
            return left.len().cmp(&right.len());
        }
        for index in (0..left.len()).rev() {
            let ordering = left[index].cmp(&right[index]);
            if ordering != Ordering::Equal {
                return ordering;
            }
        }
        Ordering::Equal
    }
}

impl PartialOrd for Nat {
    fn partial_cmp(&self, other: &Nat) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

// --------------------------------------------------------------- integers

/// A signed integer. Zero is always stored as non negative.
#[derive(Clone, PartialEq, Eq, Debug, Hash)]
pub struct Int {
    pub negative: bool,
    pub magnitude: Nat,
}

impl Int {
    pub fn from_i128(value: i128) -> Int {
        if value < 0 {
            Int { negative: true, magnitude: Nat::from_u128(value.unsigned_abs()) }
        } else {
            Int { negative: false, magnitude: Nat::from_u128(value as u128) }
        }
    }

    pub fn to_i128(&self) -> Option<i128> {
        let magnitude = self.magnitude.to_u128()?;
        if self.negative {
            if magnitude > (i128::MAX as u128) + 1 {
                None
            } else if magnitude == (i128::MAX as u128) + 1 {
                Some(i128::MIN)
            } else {
                Some(-(magnitude as i128))
            }
        } else if magnitude > i128::MAX as u128 {
            None
        } else {
            Some(magnitude as i128)
        }
    }

    /// zigzag: non negative n maps to 2n, negative n maps to 2|n| - 1.
    pub fn to_zigzag(&self) -> Nat {
        if self.negative {
            self.magnitude.double().sub_one()
        } else {
            self.magnitude.double()
        }
    }

    pub fn from_zigzag(value: &Nat) -> Int {
        if value.is_even() {
            Int { negative: false, magnitude: value.halve() }
        } else {
            let magnitude = value.add_one().halve();
            if magnitude.is_zero() {
                Int { negative: false, magnitude }
            } else {
                Int { negative: true, magnitude }
            }
        }
    }

    pub fn to_decimal(&self) -> String {
        if self.negative && !self.magnitude.is_zero() {
            format!("-{}", self.magnitude.to_decimal())
        } else {
            self.magnitude.to_decimal()
        }
    }
}

impl Ord for Int {
    fn cmp(&self, other: &Int) -> Ordering {
        match (self.negative, other.negative) {
            (false, true) => Ordering::Greater,
            (true, false) => Ordering::Less,
            (false, false) => self.magnitude.cmp(&other.magnitude),
            (true, true) => other.magnitude.cmp(&self.magnitude),
        }
    }
}

impl PartialOrd for Int {
    fn partial_cmp(&self, other: &Int) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
