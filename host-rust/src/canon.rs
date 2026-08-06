//! Canonical values, canonical bytes and canonical text.
//!
//! Decoding rejects any non canonical encoding: unknown tags, trailing bytes,
//! non minimal varints, non canonical booleans and out of order or duplicated
//! map keys.

use crate::number::{Int, Nat};
use std::cmp::Ordering;
use std::rc::Rc;

pub const DIGEST_SIZE: usize = 32;

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug, Hash)]
pub struct Digest(pub [u8; DIGEST_SIZE]);

impl Digest {
    pub fn hex(&self) -> String {
        self.0.iter().map(|b| format!("{:02x}", b)).collect()
    }

    pub fn from_hex(text: &str) -> Option<Digest> {
        if text.len() != DIGEST_SIZE * 2 {
            return None;
        }
        let mut bytes = [0u8; DIGEST_SIZE];
        for index in 0..DIGEST_SIZE {
            bytes[index] = u8::from_str_radix(&text[index * 2..index * 2 + 2], 16).ok()?;
        }
        Some(Digest(bytes))
    }
}

/// A value shares its children rather than owning them.
///
/// The Scala host holds a `Canon` by reference, so passing one into an
/// environment, matching on it or returning it costs a pointer. This host used
/// to hold the children inline, which made the same operations copy the whole
/// subtree -- and a walk over a structure of size n copy it n times. On the
/// values the first floor works with that was merely slower; on a list with a
/// cell per character it is quadratic, and the two hosts stop being
/// interchangeable in practice even though they still agree on every verdict.
///
/// `Rc` is the whole fix. Nothing about what a value *is* changes: equality is
/// still structural, the canonical order is unchanged, and the bytes a value
/// encodes to are the bytes it encoded to before.
/// A list value: a shared vector and where in it this list starts.
///
/// The offset is why it is a type rather than an `Rc<Vec<Canon>>`. Meta0's
/// only way through a list is `pcons`, which matches a head and binds the
/// rest, and copying the rest each time made walking a list quadratic in its
/// length -- which is what walking a module's declarations for every name in
/// it turned out to cost. Taking the tail is now sharing the same vector and
/// counting one further in.
///
/// Two lists are equal when the elements they are views of are equal, so the
/// offset is invisible to everything above: identity, order and the canonical
/// encoding all see the same value they saw before.
#[derive(Clone, Debug)]
pub struct List {
    items: Rc<Vec<Canon>>,
    from: usize,
}

impl List {
    pub fn new(items: Vec<Canon>) -> List {
        List { items: Rc::new(items), from: 0 }
    }

    pub fn shared(items: Rc<Vec<Canon>>) -> List {
        List { items, from: 0 }
    }

    /// Everything after the first element, sharing the same vector.
    pub fn tail(&self) -> List {
        List { items: self.items.clone(), from: (self.from + 1).min(self.items.len()) }
    }
}

impl std::ops::Deref for List {
    type Target = [Canon];
    fn deref(&self) -> &[Canon] {
        &self.items[self.from..]
    }
}

impl PartialEq for List {
    fn eq(&self, other: &List) -> bool {
        **self == **other
    }
}

impl Eq for List {}

impl From<Vec<Canon>> for List {
    fn from(items: Vec<Canon>) -> List {
        List::new(items)
    }
}

#[derive(Clone, PartialEq, Eq, Debug)]
pub enum Canon {
    Unit,
    Bool(bool),
    Nat(Nat),
    Int(Int),
    Bytes(Rc<Vec<u8>>),
    Str(Rc<str>),
    Sym(Rc<str>),
    Ref(Digest),
    List(List),
    Map(Rc<Vec<(Canon, Canon)>>),
    Node(Rc<str>, Rc<Vec<Canon>>),
}

impl Canon {
    pub fn nat(value: u128) -> Canon {
        Canon::Nat(Nat::from_u128(value))
    }

    pub fn sym(name: &str) -> Canon {
        Canon::Sym(Rc::from(name))
    }

    pub fn text(value: &str) -> Canon {
        Canon::Str(Rc::from(value))
    }

    pub fn string(value: String) -> Canon {
        Canon::Str(Rc::from(value))
    }

    pub fn symbol(name: String) -> Canon {
        Canon::Sym(Rc::from(name))
    }

    pub fn bytes(value: Vec<u8>) -> Canon {
        Canon::Bytes(Rc::new(value))
    }

    pub fn list(items: Vec<Canon>) -> Canon {
        Canon::List(List::new(items))
    }

    pub fn map(entries: Vec<(Canon, Canon)>) -> Canon {
        Canon::Map(Rc::new(entries))
    }

    pub fn node(tag: &str, args: Vec<Canon>) -> Canon {
        Canon::Node(Rc::from(tag), Rc::new(args))
    }

    pub fn tagged(tag: Rc<str>, args: Vec<Canon>) -> Canon {
        Canon::Node(tag, Rc::new(args))
    }

    pub fn ordinal(&self) -> u8 {
        match self {
            Canon::Unit => 0,
            Canon::Bool(_) => 1,
            Canon::Nat(_) => 2,
            Canon::Int(_) => 3,
            Canon::Bytes(_) => 4,
            Canon::Str(_) => 5,
            Canon::Sym(_) => 6,
            Canon::Ref(_) => 7,
            Canon::List(_) => 8,
            Canon::Map(_) => 9,
            Canon::Node(_, _) => 10,
        }
    }

    pub fn as_sym(&self) -> Option<&str> {
        match self {
            Canon::Sym(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_list(&self) -> Option<&[Canon]> {
        match self {
            Canon::List(items) => Some(items),
            _ => None,
        }
    }

    /// The declared field `(name value)` of a node.
    pub fn field(&self, name: &str) -> Option<&Canon> {
        if let Canon::Node(_, args) = self {
            for arg in args.iter() {
                if let Canon::Node(tag, inner) = arg {
                    if &**tag == name && inner.len() == 1 {
                        return Some(&inner[0]);
                    }
                }
            }
        }
        None
    }

    pub fn refs(&self, into: &mut Vec<Digest>) {
        match self {
            Canon::Ref(d) => into.push(*d),
            Canon::List(items) => items.iter().for_each(|i| i.refs(into)),
            Canon::Map(entries) => entries.iter().for_each(|(k, v)| {
                k.refs(into);
                v.refs(into);
            }),
            Canon::Node(_, args) => args.iter().for_each(|a| a.refs(into)),
            _ => {}
        }
    }
}

/// The total canonical order. Map keys are stored in this order.
pub fn compare(left: &Canon, right: &Canon) -> Ordering {
    let ordinal = left.ordinal().cmp(&right.ordinal());
    if ordinal != Ordering::Equal {
        return ordinal;
    }
    match (left, right) {
        (Canon::Unit, Canon::Unit) => Ordering::Equal,
        (Canon::Bool(a), Canon::Bool(b)) => a.cmp(b),
        (Canon::Nat(a), Canon::Nat(b)) => a.cmp(b),
        (Canon::Int(a), Canon::Int(b)) => a.cmp(b),
        (Canon::Bytes(a), Canon::Bytes(b)) => a.cmp(b),
        (Canon::Str(a), Canon::Str(b)) => a.cmp(b),
        (Canon::Sym(a), Canon::Sym(b)) => a.cmp(b),
        (Canon::Ref(a), Canon::Ref(b)) => a.0.cmp(&b.0),
        (Canon::List(a), Canon::List(b)) => compare_sequence(a, b),
        (Canon::Map(a), Canon::Map(b)) => compare_entries(a, b),
        (Canon::Node(ta, aa), Canon::Node(tb, ab)) => {
            let tag = ta.cmp(tb);
            if tag != Ordering::Equal {
                tag
            } else {
                compare_sequence(aa, ab)
            }
        }
        _ => Ordering::Equal,
    }
}

fn compare_sequence(left: &[Canon], right: &[Canon]) -> Ordering {
    let shared = left.len().min(right.len());
    for index in 0..shared {
        let ordering = compare(&left[index], &right[index]);
        if ordering != Ordering::Equal {
            return ordering;
        }
    }
    left.len().cmp(&right.len())
}

fn compare_entries(left: &[(Canon, Canon)], right: &[(Canon, Canon)]) -> Ordering {
    let shared = left.len().min(right.len());
    for index in 0..shared {
        let key = compare(&left[index].0, &right[index].0);
        if key != Ordering::Equal {
            return key;
        }
        let value = compare(&left[index].1, &right[index].1);
        if value != Ordering::Equal {
            return value;
        }
    }
    left.len().cmp(&right.len())
}

/// Sorts entries into canonical order, keeping the last binding of a key.
pub fn canonical_map(entries: Vec<(Canon, Canon)>) -> Canon {
    let mut deduplicated: Vec<(Canon, Canon)> = Vec::new();
    for (key, value) in entries {
        if let Some(slot) = deduplicated.iter_mut().find(|(k, _)| *k == key) {
            slot.1 = value;
        } else {
            deduplicated.push((key, value));
        }
    }
    deduplicated.sort_by(|a, b| compare(&a.0, &b.0));
    Canon::Map(Rc::new(deduplicated))
}

// ------------------------------------------------------------------ binary

fn write_varint(out: &mut Vec<u8>, value: &Nat) {
    for index in 0..value.chunks.len() {
        let last = index + 1 == value.chunks.len();
        out.push(if last { value.chunks[index] } else { value.chunks[index] | 0x80 });
    }
}

fn write_length(out: &mut Vec<u8>, length: usize) {
    write_varint(out, &Nat::from_u128(length as u128));
}

pub fn encode(value: &Canon) -> Vec<u8> {
    let mut out = Vec::new();
    encode_into(value, &mut out);
    out
}

fn encode_into(value: &Canon, out: &mut Vec<u8>) {
    match value {
        Canon::Unit => out.push(0),
        Canon::Bool(b) => {
            out.push(1);
            out.push(if *b { 1 } else { 0 });
        }
        Canon::Nat(n) => {
            out.push(2);
            write_varint(out, n);
        }
        Canon::Int(z) => {
            out.push(3);
            write_varint(out, &z.to_zigzag());
        }
        Canon::Bytes(b) => {
            out.push(4);
            write_length(out, b.len());
            out.extend_from_slice(b);
        }
        Canon::Str(s) => {
            out.push(5);
            write_length(out, s.as_bytes().len());
            out.extend_from_slice(s.as_bytes());
        }
        Canon::Sym(s) => {
            out.push(6);
            write_length(out, s.as_bytes().len());
            out.extend_from_slice(s.as_bytes());
        }
        Canon::Ref(d) => {
            out.push(7);
            out.extend_from_slice(&d.0);
        }
        Canon::List(items) => {
            out.push(8);
            write_length(out, items.len());
            items.iter().for_each(|item| encode_into(item, out));
        }
        Canon::Map(entries) => {
            out.push(9);
            write_length(out, entries.len());
            entries.iter().for_each(|(k, v)| {
                encode_into(k, out);
                encode_into(v, out);
            });
        }
        Canon::Node(tag, args) => {
            out.push(10);
            write_length(out, tag.as_bytes().len());
            out.extend_from_slice(tag.as_bytes());
            write_length(out, args.len());
            args.iter().for_each(|a| encode_into(a, out));
        }
    }
}

struct Cursor<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl<'a> Cursor<'a> {
    fn byte(&mut self) -> Result<u8, String> {
        if self.position >= self.bytes.len() {
            return Err("unexpected end of input".to_string());
        }
        let byte = self.bytes[self.position];
        self.position += 1;
        Ok(byte)
    }

    fn take(&mut self, count: usize) -> Result<&'a [u8], String> {
        if self.position + count > self.bytes.len() {
            return Err("unexpected end of input".to_string());
        }
        let slice = &self.bytes[self.position..self.position + count];
        self.position += count;
        Ok(slice)
    }

    /// Reads a varint of any length and rejects a non minimal encoding.
    fn varint(&mut self) -> Result<Nat, String> {
        let mut chunks = Vec::new();
        loop {
            let byte = self.byte()?;
            chunks.push(byte & 0x7f);
            if byte & 0x80 == 0 {
                break;
            }
        }
        let value = Nat { chunks };
        if !value.is_minimal() {
            return Err("non-canonical varint rejected".to_string());
        }
        Ok(value)
    }

    fn length(&mut self) -> Result<usize, String> {
        let value = self.varint()?;
        match value.to_u128() {
            Some(v) if v <= usize::MAX as u128 => Ok(v as usize),
            _ => Err("length out of range".to_string()),
        }
    }
}

fn read(cursor: &mut Cursor) -> Result<Canon, String> {
    match cursor.byte()? {
        0 => Ok(Canon::Unit),
        1 => match cursor.byte()? {
            0 => Ok(Canon::Bool(false)),
            1 => Ok(Canon::Bool(true)),
            _ => Err("non-canonical boolean".to_string()),
        },
        2 => Ok(Canon::Nat(cursor.varint()?)),
        3 => Ok(Canon::Int(Int::from_zigzag(&cursor.varint()?))),
        4 => {
            let length = cursor.length()?;
            Ok(Canon::Bytes(Rc::new(cursor.take(length)?.to_vec())))
        }
        5 => {
            let length = cursor.length()?;
            let bytes = cursor.take(length)?.to_vec();
            String::from_utf8(bytes).map(Canon::string).map_err(|_| "invalid utf8".to_string())
        }
        6 => {
            let length = cursor.length()?;
            let bytes = cursor.take(length)?.to_vec();
            String::from_utf8(bytes).map(Canon::symbol).map_err(|_| "invalid utf8".to_string())
        }
        7 => {
            let bytes = cursor.take(DIGEST_SIZE)?;
            let mut digest = [0u8; DIGEST_SIZE];
            digest.copy_from_slice(bytes);
            Ok(Canon::Ref(Digest(digest)))
        }
        8 => {
            let count = cursor.length()?;
            let mut items = Vec::new();
            for _ in 0..count {
                items.push(read(cursor)?);
            }
            Ok(Canon::list(items))
        }
        9 => {
            let count = cursor.length()?;
            let mut entries = Vec::new();
            for _ in 0..count {
                let key = read(cursor)?;
                let value = read(cursor)?;
                entries.push((key, value));
            }
            for window in entries.windows(2) {
                match compare(&window[0].0, &window[1].0) {
                    Ordering::Less => {}
                    Ordering::Equal => return Err("duplicate map key rejected".to_string()),
                    Ordering::Greater => return Err("unordered map key rejected".to_string()),
                }
            }
            Ok(Canon::Map(Rc::new(entries)))
        }
        10 => {
            let length = cursor.length()?;
            let bytes = cursor.take(length)?.to_vec();
            let tag = String::from_utf8(bytes).map_err(|_| "invalid utf8".to_string())?;
            let count = cursor.length()?;
            let mut args = Vec::new();
            for _ in 0..count {
                args.push(read(cursor)?);
            }
            Ok(Canon::Node(Rc::from(tag), Rc::new(args)))
        }
        other => Err(format!("unknown canonical tag {}", other)),
    }
}

pub fn decode(bytes: &[u8]) -> Result<Canon, String> {
    let mut cursor = Cursor { bytes, position: 0 };
    let value = read(&mut cursor)?;
    if cursor.position != bytes.len() {
        return Err("trailing bytes after canonical value".to_string());
    }
    if encode(&value) != bytes {
        return Err("non-canonical encoding rejected".to_string());
    }
    Ok(value)
}

// -------------------------------------------------------------------- text

pub fn write_text(value: &Canon) -> String {
    let mut out = String::new();
    write_text_into(value, &mut out);
    out
}

fn write_text_into(value: &Canon, out: &mut String) {
    match value {
        Canon::Unit => out.push_str("#unit"),
        Canon::Bool(b) => out.push_str(if *b { "#t" } else { "#f" }),
        Canon::Nat(n) => out.push_str(&n.to_decimal()),
        Canon::Int(z) => {
            if !z.negative {
                out.push('+');
            }
            out.push_str(&z.to_decimal());
        }
        Canon::Bytes(b) => {
            out.push_str("#x");
            b.iter().for_each(|byte| out.push_str(&format!("{:02x}", byte)));
        }
        Canon::Str(s) => {
            out.push('"');
            for character in s.chars() {
                match character {
                    '"' => out.push_str("\\\""),
                    '\\' => out.push_str("\\\\"),
                    '\n' => out.push_str("\\n"),
                    '\t' => out.push_str("\\t"),
                    '\r' => out.push_str("\\r"),
                    other => out.push(other),
                }
            }
            out.push('"');
        }
        Canon::Sym(s) => out.push_str(s),
        Canon::Ref(d) => {
            out.push_str("#d");
            out.push_str(&d.hex());
        }
        Canon::List(items) => {
            out.push('[');
            for (index, item) in items.iter().enumerate() {
                if index > 0 {
                    out.push(' ');
                }
                write_text_into(item, out);
            }
            out.push(']');
        }
        Canon::Map(entries) => {
            out.push('{');
            for (index, (key, value)) in entries.iter().enumerate() {
                if index > 0 {
                    out.push(' ');
                }
                write_text_into(key, out);
                out.push(' ');
                write_text_into(value, out);
            }
            out.push('}');
        }
        Canon::Node(tag, args) => {
            out.push('(');
            out.push_str(tag);
            for arg in args.iter() {
                out.push(' ');
                write_text_into(arg, out);
            }
            out.push(')');
        }
    }
}

// ------------------------------------------------------------- text reader

pub fn read_text(input: &str) -> Result<Canon, String> {
    let mut reader = TextReader { bytes: input.as_bytes(), position: 0 };
    reader.skip_trivia();
    let value = reader.value()?;
    reader.skip_trivia();
    if reader.position != reader.bytes.len() {
        return Err(format!("trailing input at offset {}", reader.position));
    }
    Ok(value)
}

struct TextReader<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl<'a> TextReader<'a> {
    fn peek(&self) -> Option<u8> {
        self.bytes.get(self.position).copied()
    }

    fn skip_trivia(&mut self) {
        loop {
            while matches!(self.peek(), Some(b) if b.is_ascii_whitespace()) {
                self.position += 1;
            }
            if self.peek() == Some(b';') {
                while matches!(self.peek(), Some(b) if b != b'\n') {
                    self.position += 1;
                }
                continue;
            }
            break;
        }
    }

    fn value(&mut self) -> Result<Canon, String> {
        self.skip_trivia();
        match self.peek() {
            None => Err("unexpected end of input".to_string()),
            Some(b'(') => self.node(),
            Some(b'[') => self.list(),
            Some(b'{') => self.map(),
            Some(b'"') => self.string().map(Canon::string),
            Some(b'#') => self.hash(),
            Some(other) if other == b')' || other == b']' || other == b'}' => {
                Err(format!("unexpected '{}'", other as char))
            }
            Some(_) => self.atom(),
        }
    }

    fn node(&mut self) -> Result<Canon, String> {
        self.position += 1;
        self.skip_trivia();
        let tag = match self.value()? {
            Canon::Sym(name) => name,
            other => return Err(format!("node tag must be a symbol, found {}", write_text(&other))),
        };
        let mut args = Vec::new();
        self.skip_trivia();
        while self.peek().is_some() && self.peek() != Some(b')') {
            args.push(self.value()?);
            self.skip_trivia();
        }
        if self.peek() != Some(b')') {
            return Err("unterminated node".to_string());
        }
        self.position += 1;
        Ok(Canon::Node(tag, Rc::new(args)))
    }

    fn list(&mut self) -> Result<Canon, String> {
        self.position += 1;
        let mut items = Vec::new();
        self.skip_trivia();
        while self.peek().is_some() && self.peek() != Some(b']') {
            items.push(self.value()?);
            self.skip_trivia();
        }
        if self.peek() != Some(b']') {
            return Err("unterminated list".to_string());
        }
        self.position += 1;
        Ok(Canon::list(items))
    }

    fn map(&mut self) -> Result<Canon, String> {
        self.position += 1;
        let mut entries = Vec::new();
        self.skip_trivia();
        while self.peek().is_some() && self.peek() != Some(b'}') {
            let key = self.value()?;
            self.skip_trivia();
            if self.peek().is_none() || self.peek() == Some(b'}') {
                return Err("map entry missing value".to_string());
            }
            let value = self.value()?;
            entries.push((key, value));
            self.skip_trivia();
        }
        if self.peek() != Some(b'}') {
            return Err("unterminated map".to_string());
        }
        self.position += 1;
        Ok(canonical_map(entries))
    }

    fn string(&mut self) -> Result<String, String> {
        self.position += 1;
        let mut out = String::new();
        loop {
            match self.peek() {
                None => return Err("unterminated string".to_string()),
                Some(b'"') => {
                    self.position += 1;
                    return Ok(out);
                }
                Some(b'\\') => {
                    self.position += 1;
                    match self.peek() {
                        Some(b'n') => out.push('\n'),
                        Some(b't') => out.push('\t'),
                        Some(b'r') => out.push('\r'),
                        Some(b'\\') => out.push('\\'),
                        Some(b'"') => out.push('"'),
                        Some(other) => return Err(format!("unknown escape \\{}", other as char)),
                        None => return Err("unterminated escape".to_string()),
                    }
                    self.position += 1;
                }
                Some(_) => {
                    let start = self.position;
                    while matches!(self.peek(), Some(b) if b != b'"' && b != b'\\') {
                        self.position += 1;
                    }
                    out.push_str(std::str::from_utf8(&self.bytes[start..self.position]).map_err(|_| "invalid utf8".to_string())?);
                }
            }
        }
    }

    fn token(&mut self) -> String {
        let start = self.position;
        while let Some(byte) = self.peek() {
            if byte.is_ascii_whitespace() || b"()[]{}\";".contains(&byte) {
                break;
            }
            self.position += 1;
        }
        String::from_utf8_lossy(&self.bytes[start..self.position]).to_string()
    }

    fn hash(&mut self) -> Result<Canon, String> {
        self.position += 1;
        let token = self.token();
        if token == "unit" {
            Ok(Canon::Unit)
        } else if token == "t" {
            Ok(Canon::Bool(true))
        } else if token == "f" {
            Ok(Canon::Bool(false))
        } else if let Some(hex) = token.strip_prefix('d') {
            Digest::from_hex(hex).map(Canon::Ref).ok_or_else(|| format!("not a canonical digest: {}", hex))
        } else if let Some(hex) = token.strip_prefix('x') {
            if hex.len() % 2 != 0 {
                return Err("odd-length byte literal".to_string());
            }
            let mut bytes = Vec::new();
            for index in (0..hex.len()).step_by(2) {
                bytes.push(u8::from_str_radix(&hex[index..index + 2], 16).map_err(|_| "bad byte literal")?);
            }
            Ok(Canon::Bytes(Rc::new(bytes)))
        } else {
            Err(format!("unknown # literal: #{}", token))
        }
    }

    fn atom(&mut self) -> Result<Canon, String> {
        let token = self.token();
        if token.is_empty() {
            return Err("empty token".to_string());
        }
        if token.bytes().all(|b| b.is_ascii_digit()) {
            return Ok(Canon::Nat(Nat::from_decimal(&token).ok_or("bad natural")?));
        }
        if (token.starts_with('-') || token.starts_with('+')) && token.len() > 1 && token[1..].bytes().all(|b| b.is_ascii_digit()) {
            let magnitude = Nat::from_decimal(&token[1..]).ok_or("bad integer")?;
            return Ok(Canon::Int(Int { negative: token.starts_with('-'), magnitude }));
        }
        Ok(Canon::Sym(Rc::from(token)))
    }
}
