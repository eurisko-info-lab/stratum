// An independent bootstrap host for Stratum.
//
// This implementation shares no code with the Scala host. It decodes canonical
// artifacts, recomputes identity, traverses a closure, verifies the structure
// of a foundation and emits a canonical verdict. Its output bytes must equal
// the Scala host's for the same foundation.
//
// It deliberately has no dependencies.

use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

// ----------------------------------------------------------------- sha-256

const K: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

fn sha256(input: &[u8]) -> [u8; 32] {
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
    ];
    let mut message = input.to_vec();
    let bit_length = (input.len() as u64) * 8;
    message.push(0x80);
    while message.len() % 64 != 56 {
        message.push(0);
    }
    message.extend_from_slice(&bit_length.to_be_bytes());

    for chunk in message.chunks(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([chunk[i * 4], chunk[i * 4 + 1], chunk[i * 4 + 2], chunk[i * 4 + 3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let (mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh) =
            (h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]);
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let temp1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = s0.wrapping_add(maj);
            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
        h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g);
        h[7] = h[7].wrapping_add(hh);
    }

    let mut out = [0u8; 32];
    for i in 0..8 {
        out[i * 4..i * 4 + 4].copy_from_slice(&h[i].to_be_bytes());
    }
    out
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

// ------------------------------------------------------------------- canon

#[derive(Clone, PartialEq, Eq, PartialOrd, Ord, Debug)]
enum Canon {
    Unit,
    Bool(bool),
    Nat(u128),
    Int(i128),
    Bytes(Vec<u8>),
    Str(String),
    Sym(String),
    Ref([u8; 32]),
    List(Vec<Canon>),
    Map(Vec<(Canon, Canon)>),
    Node(String, Vec<Canon>),
}

struct Cursor<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn byte(&mut self) -> Result<u8, String> {
        if self.pos >= self.bytes.len() {
            return Err("unexpected end of input".to_string());
        }
        let b = self.bytes[self.pos];
        self.pos += 1;
        Ok(b)
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        if self.pos + n > self.bytes.len() {
            return Err("unexpected end of input".to_string());
        }
        let slice = &self.bytes[self.pos..self.pos + n];
        self.pos += n;
        Ok(slice)
    }

    fn varint(&mut self) -> Result<u128, String> {
        let mut shift = 0u32;
        let mut result: u128 = 0;
        loop {
            let b = self.byte()?;
            result |= ((b & 0x7f) as u128) << shift;
            if b & 0x80 == 0 {
                break;
            }
            shift += 7;
            if shift > 126 {
                return Err("varint too long for this host".to_string());
            }
        }
        Ok(result)
    }
}

fn write_varint(out: &mut Vec<u8>, value: u128) {
    let mut v = value;
    loop {
        let b = (v & 0x7f) as u8;
        v >>= 7;
        if v == 0 {
            out.push(b);
            break;
        } else {
            out.push(b | 0x80);
        }
    }
}

fn encode(value: &Canon, out: &mut Vec<u8>) {
    match value {
        Canon::Unit => out.push(0),
        Canon::Bool(b) => {
            out.push(1);
            out.push(if *b { 1 } else { 0 });
        }
        Canon::Nat(n) => {
            out.push(2);
            write_varint(out, *n);
        }
        Canon::Int(z) => {
            out.push(3);
            let zig = if *z >= 0 {
                (*z as u128) * 2
            } else {
                ((-*z) as u128) * 2 - 1
            };
            write_varint(out, zig);
        }
        Canon::Bytes(b) => {
            out.push(4);
            write_varint(out, b.len() as u128);
            out.extend_from_slice(b);
        }
        Canon::Str(s) => {
            out.push(5);
            write_varint(out, s.as_bytes().len() as u128);
            out.extend_from_slice(s.as_bytes());
        }
        Canon::Sym(s) => {
            out.push(6);
            write_varint(out, s.as_bytes().len() as u128);
            out.extend_from_slice(s.as_bytes());
        }
        Canon::Ref(d) => {
            out.push(7);
            out.extend_from_slice(d);
        }
        Canon::List(items) => {
            out.push(8);
            write_varint(out, items.len() as u128);
            for item in items {
                encode(item, out);
            }
        }
        Canon::Map(entries) => {
            out.push(9);
            write_varint(out, entries.len() as u128);
            for (k, v) in entries {
                encode(k, out);
                encode(v, out);
            }
        }
        Canon::Node(tag, args) => {
            out.push(10);
            write_varint(out, tag.as_bytes().len() as u128);
            out.extend_from_slice(tag.as_bytes());
            write_varint(out, args.len() as u128);
            for a in args {
                encode(a, out);
            }
        }
    }
}

fn read(cur: &mut Cursor) -> Result<Canon, String> {
    let tag = cur.byte()?;
    match tag {
        0 => Ok(Canon::Unit),
        1 => match cur.byte()? {
            0 => Ok(Canon::Bool(false)),
            1 => Ok(Canon::Bool(true)),
            _ => Err("non-canonical boolean".to_string()),
        },
        2 => Ok(Canon::Nat(cur.varint()?)),
        3 => {
            let zig = cur.varint()?;
            let value = if zig % 2 == 0 {
                (zig / 2) as i128
            } else {
                -(((zig + 1) / 2) as i128)
            };
            Ok(Canon::Int(value))
        }
        4 => {
            let len = cur.varint()? as usize;
            Ok(Canon::Bytes(cur.take(len)?.to_vec()))
        }
        5 => {
            let len = cur.varint()? as usize;
            let bytes = cur.take(len)?.to_vec();
            String::from_utf8(bytes).map(Canon::Str).map_err(|_| "invalid utf8".to_string())
        }
        6 => {
            let len = cur.varint()? as usize;
            let bytes = cur.take(len)?.to_vec();
            String::from_utf8(bytes).map(Canon::Sym).map_err(|_| "invalid utf8".to_string())
        }
        7 => {
            let bytes = cur.take(32)?;
            let mut d = [0u8; 32];
            d.copy_from_slice(bytes);
            Ok(Canon::Ref(d))
        }
        8 => {
            let n = cur.varint()? as usize;
            let mut items = Vec::with_capacity(n);
            for _ in 0..n {
                items.push(read(cur)?);
            }
            Ok(Canon::List(items))
        }
        9 => {
            let n = cur.varint()? as usize;
            let mut entries = Vec::with_capacity(n);
            for _ in 0..n {
                let k = read(cur)?;
                let v = read(cur)?;
                entries.push((k, v));
            }
            Ok(Canon::Map(entries))
        }
        10 => {
            let len = cur.varint()? as usize;
            let bytes = cur.take(len)?.to_vec();
            let name = String::from_utf8(bytes).map_err(|_| "invalid utf8".to_string())?;
            let n = cur.varint()? as usize;
            let mut args = Vec::with_capacity(n);
            for _ in 0..n {
                args.push(read(cur)?);
            }
            Ok(Canon::Node(name, args))
        }
        other => Err(format!("unknown canonical tag {}", other)),
    }
}

fn decode(bytes: &[u8]) -> Result<Canon, String> {
    let mut cur = Cursor { bytes, pos: 0 };
    let value = read(&mut cur)?;
    if cur.pos != bytes.len() {
        return Err("trailing bytes after canonical value".to_string());
    }
    let mut re = Vec::new();
    encode(&value, &mut re);
    if re != bytes {
        return Err("non-canonical encoding rejected".to_string());
    }
    Ok(value)
}

fn write_text(value: &Canon, out: &mut String) {
    match value {
        Canon::Unit => out.push_str("#unit"),
        Canon::Bool(b) => out.push_str(if *b { "#t" } else { "#f" }),
        Canon::Nat(n) => out.push_str(&n.to_string()),
        Canon::Int(z) => {
            if *z >= 0 {
                out.push('+');
            }
            out.push_str(&z.to_string());
        }
        Canon::Bytes(b) => {
            out.push_str("#x");
            out.push_str(&hex(b));
        }
        Canon::Str(s) => {
            out.push('"');
            for c in s.chars() {
                match c {
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
            out.push_str(&hex(d));
        }
        Canon::List(items) => {
            out.push('[');
            for (i, item) in items.iter().enumerate() {
                if i > 0 {
                    out.push(' ');
                }
                write_text(item, out);
            }
            out.push(']');
        }
        Canon::Map(entries) => {
            out.push('{');
            for (i, (k, v)) in entries.iter().enumerate() {
                if i > 0 {
                    out.push(' ');
                }
                write_text(k, out);
                out.push(' ');
                write_text(v, out);
            }
            out.push('}');
        }
        Canon::Node(tag, args) => {
            out.push('(');
            out.push_str(tag);
            for a in args {
                out.push(' ');
                write_text(a, out);
            }
            out.push(')');
        }
    }
}

// --------------------------------------------------------------- artifacts

fn refs_of(value: &Canon, into: &mut Vec<[u8; 32]>) {
    match value {
        Canon::Ref(d) => into.push(*d),
        Canon::List(items) => items.iter().for_each(|i| refs_of(i, into)),
        Canon::Map(entries) => entries.iter().for_each(|(k, v)| {
            refs_of(k, into);
            refs_of(v, into);
        }),
        Canon::Node(_, args) => args.iter().for_each(|a| refs_of(a, into)),
        _ => {}
    }
}

struct Artifact {
    kind: String,
    body: Canon,
}

fn artifact_of(value: Canon) -> Result<Artifact, String> {
    match value {
        Canon::Node(tag, mut args) if tag == "artifact" && args.len() == 2 => {
            let body = args.pop().unwrap();
            match args.pop().unwrap() {
                Canon::Sym(kind) => Ok(Artifact { kind, body }),
                _ => Err("artifact kind is not a symbol".to_string()),
            }
        }
        _ => Err("not an artifact envelope".to_string()),
    }
}

struct Cas {
    root: PathBuf,
}

impl Cas {
    fn get(&self, digest: &[u8; 32]) -> Result<Artifact, String> {
        let path = self.root.join(format!("{}.canon", hex(digest)));
        let bytes = fs::read(&path).map_err(|_| format!("missing artifact {}", hex(digest)))?;
        if sha256(&bytes) != *digest {
            return Err(format!("artifact {} does not match its digest", hex(digest)));
        }
        artifact_of(decode(&bytes)?)
    }
}

fn field<'a>(value: &'a Canon, name: &str) -> Option<&'a Canon> {
    if let Canon::Node(_, args) = value {
        for arg in args {
            if let Canon::Node(tag, inner) = arg {
                if tag == name && inner.len() == 1 {
                    return Some(&inner[0]);
                }
            }
        }
    }
    None
}

// ------------------------------------------------------------ verification

fn verify(dir: &Path) -> Result<Canon, String> {
    let digest_text = fs::read_to_string(dir.join("digest.txt"))
        .map_err(|_| "no digest.txt".to_string())?;
    let trimmed = digest_text.trim();
    if trimmed.len() != 64 {
        return Err("digest.txt is not a canonical digest".to_string());
    }
    let mut root = [0u8; 32];
    for i in 0..32 {
        root[i] = u8::from_str_radix(&trimmed[i * 2..i * 2 + 2], 16)
            .map_err(|_| "digest.txt is not hexadecimal".to_string())?;
    }

    let cas = Cas { root: dir.join("closure") };

    let mut seen: BTreeSet<[u8; 32]> = BTreeSet::new();
    let mut kinds: BTreeMap<String, u64> = BTreeMap::new();
    let mut stack = vec![root];
    while let Some(digest) = stack.pop() {
        if seen.contains(&digest) {
            continue;
        }
        let artifact = cas.get(&digest)?;
        seen.insert(digest);
        *kinds.entry(artifact.kind.clone()).or_insert(0) += 1;
        let mut refs = Vec::new();
        refs_of(&artifact.body, &mut refs);
        for r in refs {
            if !seen.contains(&r) {
                stack.push(r);
            }
        }
    }

    let foundation = cas.get(&root)?;
    if foundation.kind != "foundation" {
        return Err("root artifact is not a foundation".to_string());
    }
    let name = match field(&foundation.body, "name") {
        Some(Canon::Str(s)) => s.clone(),
        _ => return Err("foundation has no name".to_string()),
    };
    let application_ref = match field(&foundation.body, "application") {
        Some(Canon::Ref(d)) => *d,
        _ => return Err("foundation has no application reference".to_string()),
    };
    let application = cas.get(&application_ref)?;
    if application.kind != "application" {
        return Err("application artifact has the wrong kind".to_string());
    }
    let meta_ref = match field(&application.body, "meta") {
        Some(Canon::Ref(d)) => *d,
        _ => return Err("application has no meta program reference".to_string()),
    };
    let meta = cas.get(&meta_ref)?;
    if meta.kind != "meta-program" {
        return Err("meta program artifact has the wrong kind".to_string());
    }

    let kind_entries: Vec<(Canon, Canon)> = kinds
        .iter()
        .map(|(k, v)| (Canon::Sym(k.clone()), Canon::Nat(*v as u128)))
        .collect();

    Ok(Canon::Node(
        "attestation".to_string(),
        vec![
            Canon::Node("name".to_string(), vec![Canon::Str(name)]),
            Canon::Node("foundation".to_string(), vec![Canon::Ref(root)]),
            Canon::Node("application".to_string(), vec![Canon::Ref(application_ref)]),
            Canon::Node("meta".to_string(), vec![Canon::Ref(meta_ref)]),
            Canon::Node("closure".to_string(), vec![Canon::Nat(seen.len() as u128)]),
            Canon::Node("kinds".to_string(), vec![Canon::Map(kind_entries)]),
            Canon::Node("complete".to_string(), vec![Canon::Bool(true)]),
        ],
    ))
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 3 || args[1] != "attest" {
        eprintln!("usage: stratum-verify attest <foundation-dir>");
        std::process::exit(2);
    }
    match verify(Path::new(&args[2])) {
        Ok(attestation) => {
            let mut text = String::new();
            write_text(&attestation, &mut text);
            let mut bytes = Vec::new();
            encode(&attestation, &mut bytes);
            println!("{}", text);
            println!("attestation {}", hex(&sha256(&bytes)));
        }
        Err(message) => {
            println!("error: {}", message);
            std::process::exit(1);
        }
    }
}
